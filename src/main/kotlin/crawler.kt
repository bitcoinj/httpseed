/*
 * Copyright by the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.plan99.bitcoin.cartographer

import com.google.common.io.BaseEncoding
import com.google.common.primitives.UnsignedBytes
import net.jcip.annotations.GuardedBy
import org.bitcoinj.core.AddressMessage
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Message
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Peer
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.core.VersionMessage
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.net.NioClientManager
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.Threading
import org.mapdb.DBMaker
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.io.Serializable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.Arrays
import java.util.Collections
import java.util.LinkedList
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.DelayQueue
import java.util.concurrent.Delayed
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

enum class PeerStatus {
    UNTESTED,
    UNREACHABLE,
    BEHIND, // Not caught up with the block chain
    OK
}

data class PeerData(val status: PeerStatus, val serviceBits: Long, val lastCrawlTime: Instant, val lastSuccessTime: Instant? = null, val supportsGetUTXO: Boolean = false) : Serializable {
    fun isTimeToRecrawl(recrawlMinutes: Long): Boolean {
        val ago = Instant.now().minusSeconds(recrawlMinutes * 60)
        return this.lastCrawlTime.isBefore(ago)
    }

    // We recrawl nodes that are currently up to check they're still alive, or nodes which *were* up within the last day
    // but have disappeared to see if they come back, or nodes that were behind when we last checked them.
    fun shouldRecrawl() = when (status) {
        PeerStatus.OK, PeerStatus.BEHIND, PeerStatus.UNTESTED -> true
        PeerStatus.UNREACHABLE -> lastSuccessTime != null && lastSuccessTime.isAfter(Instant.now() - Duration.ofDays(1))
    }
}

private class PeerDataSerializer : Serializer<PeerData> {
    override fun serialize(out: DataOutput2, value: PeerData) {
        out.writeUTF(value.status.name)
        out.writeLong(value.serviceBits)
        out.writeLong(value.lastCrawlTime.toEpochMilli())
        out.writeLong(value.lastSuccessTime?.toEpochMilli() ?: 0L)
        out.writeBoolean(value.supportsGetUTXO)
    }

    override fun deserialize(input: DataInput2, available: Int): PeerData {
        val status = PeerStatus.valueOf(input.readUTF())
        val serviceBits = input.readLong()
        val lastCrawlTime = Instant.ofEpochMilli(input.readLong())
        val lastSuccessTimeLong = input.readLong();
        val lastSuccessTime = if (lastSuccessTimeLong == 0L) null else Instant.ofEpochMilli(lastSuccessTimeLong)
        val supportsGetUTXO = input.readBoolean()
        return PeerData(status, serviceBits, lastCrawlTime, lastSuccessTime, supportsGetUTXO)
    }
}

// Crawler engine
class Crawler(private val console: Console, private val workingDir: Path, public val params: NetworkParameters, private val hostname: String) {
    private val log: Logger = LoggerFactory.getLogger("cartographer.engine")

    private val kit = WalletAppKit(params, workingDir.toFile(), "cartographer")
    private val db = DBMaker.fileDB(workingDir.resolve("crawlerdb").toFile()).make()
    public val addrMap: ConcurrentMap<InetSocketAddress, PeerData> =
            db.hashMap("addrToStatus").valueSerializer(PeerDataSerializer()).createOrOpen() as ConcurrentMap<InetSocketAddress, PeerData>
    @GuardedBy("this") private val okPeers: LinkedList<InetSocketAddress> = LinkedList()

    private val connecting: MutableSet<InetSocketAddress> = Collections.synchronizedSet(HashSet())

    private val ccm = NioClientManager()
    private val verMsg: VersionMessage = VersionMessage(params, -1)

    // Rate limiting
    private var openConnections = 0
    private val maxConnections = 200
    data class LightweightAddress(public val addr: ByteArray, public val port: Short) {
        fun toInetSocketAddress() = InetSocketAddress(InetAddress.getByAddress(addr), port.toInt())
    }
    fun InetSocketAddress.toLightweight() = LightweightAddress(this.address.address, this.port.toShort())
    private val addressQueue = HashSet<LightweightAddress>()

    // Recrawl queue
    inner class PendingRecrawl(val addr: InetSocketAddress) : Delayed {
        private fun nowSeconds() = (System.currentTimeMillis() / 1000).toInt()
        private val creationTime = nowSeconds()

        override fun compareTo(other: Delayed?): Int = creationTime.compareTo((other as PendingRecrawl).creationTime)
        override fun getDelay(unit: TimeUnit): Long =
            unit.convert(creationTime + (console.recrawlMinutes * 60) - nowSeconds(), TimeUnit.SECONDS)

        fun delayAsString() = Duration.ofSeconds(getDelay(TimeUnit.SECONDS)).toString().substring(2).replace("M", " minutes ").replace("S", " seconds")
        override fun toString() = "${addr.toString().substring(1)} in ${delayAsString()}"
    }
    private val recrawlQueue = DelayQueue<PendingRecrawl>()

    public fun snapshotRecrawlQueue(): Iterator<PendingRecrawl> = recrawlQueue.iterator()   // Snapshots internally

    public fun start() {
        console.crawler = this

        loadFromDB()

        val VERSION = "1.2"
        val PRODUCT_NAME = "Cartographer"

        verMsg.appendToSubVer(PRODUCT_NAME, VERSION, hostname)

        // We use the low level networking API to crawl, because PeerGroup does things like backoff/retry/etc which we don't want.
        log.info("Starting crawl network manager")
        ccm.startAsync().awaitRunning()

        // We use a regular WAK setup to learn about the state of the network but not to crawl it.
        log.info("Waiting for block chain headers to sync ...")
        kit.startAsync().awaitRunning()
        kit.peerGroup().setUserAgent(PRODUCT_NAME, VERSION, hostname)
        log.info("Chain synced, querying initial addresses")
        val peer = kit.peerGroup().waitForPeers(1).get()[0]

        // When we receive an addr broadcast from our long-term network connections, queue up the addresses for crawling.
        kit.peerGroup().addPreMessageReceivedEventListener(Threading.SAME_THREAD, object : PreMessageReceivedEventListener {
            override fun onPreMessageReceived(peer: Peer, m: Message): Message {
                if (m is AddressMessage) {
                    Threading.USER_THREAD.execute {
                        val sockaddrs = m.addresses.map { it.socketAddress }
                        val fresh = sockaddrs.filterNot { addrMap.containsKey(it) or addressQueue.contains(it.toLightweight()) }
                        if (fresh.isNotEmpty()) {
                            log.info("Got ${fresh.size} new address(es) from $peer" + if (fresh.size < 10) ": " + fresh.joinToString(",") else "")
                            queueAddrs(fresh)
                            crawl()
                        }
                    }
                }
                return m
            }
        })

        if (okPeers.isEmpty()) {
            // First run: request some addresses. Response will be handled by the event listener above.
            peer.addr
        } else {
            // Pick some peers that were considered OK on the last run and recrawl them immediately to kick things off again.
            log.info("Kicking off crawl with some peers from previous run")
            console.numOKPeers = okPeers.size
            Threading.USER_THREAD.execute() {
                okPeers.take(20).forEach { attemptConnect(it) }
            }
        }

        thread(name = "Recrawl thread") {
            while (true) {
                queueAndCrawl(recrawlQueue.take().addr)
            }
        }
    }

    fun stop() {
        log.info("Stopping ...")
        db.close()
    }

    fun crawl() {
        while (openConnections < maxConnections) {
            val lightAddr: LightweightAddress? = addressQueue.firstOrNull()
            if (lightAddr == null) break
            addressQueue.remove(lightAddr)
            console.numPendingAddrs = addressQueue.size
            // Some addr messages have bogus port values in them; ignore.
            if (lightAddr.port == 0.toShort()) continue

            val addr = lightAddr.toInetSocketAddress()
            if (connecting.contains(addr)) continue

            val data = addrMap[addr]
            var doConnect = if (data == null) {
                // Not seen this address before and not already probing it
                addrMap[addr] = PeerData(PeerStatus.UNTESTED, 0, Instant.now())
                console.numKnownAddresses++
                db.commit()
                true
            } else {
                data.shouldRecrawl() && data.isTimeToRecrawl(console.recrawlMinutes)
            }

            if (doConnect)
                attemptConnect(addr)
        }
    }

    private fun markAs(addr: InetSocketAddress, status: PeerStatus): PeerStatus {
        val cur = addrMap[addr]!!
        addrMap[addr] = cur.copy(status = status, lastCrawlTime = Instant.now())
        db.commit()
        synchronized(this) {
            okPeers.remove(addr)
            console.numOKPeers = okPeers.size
        }
        return cur.status
    }

    private fun markAsOK(addr: InetSocketAddress, peer: Peer) {
        val peerData: PeerData? = addrMap[addr]
        val oldStatus = peerData?.status
        if (oldStatus == PeerStatus.UNREACHABLE && peerData.lastSuccessTime != null)
            log.info("Peer $addr came back from the dead")
        var newData = PeerData(
                status = PeerStatus.OK,
                lastCrawlTime = Instant.now(),
                serviceBits = peer.peerVersionMessage.localServices,
                lastSuccessTime = Instant.now()
        )
        addrMap[addr] = newData
        if (peerData == null)
            console.numKnownAddresses++
        db.commit()

        // We might have recrawled an OK peer if forced via JMX.
        if (oldStatus != PeerStatus.OK) {
            synchronized(this) {
                okPeers.add(addr)
                console.numOKPeers = okPeers.size
            }
        }
    }

    fun attemptConnect(addr: InetSocketAddress) {
        connecting.add(addr)
        val peer = Peer(params, verMsg, null, PeerAddress(params, addr))
        peer.versionHandshakeFuture later { p ->
            onConnect(addr, p)
        }
        // Possibly pause a moment to stay within our connects/sec budget.
        val pauseTime = console.connectsRateLimiter.acquire()
        console.recordPauseTime(pauseTime)
        openConnections++
        console.recordConnectAttempt()
        ccm.openConnection(addr, peer) later { sockaddr, error ->
            if (error != null) {
                connecting.remove(sockaddr!! as InetSocketAddress)
                if (markAs(addr, PeerStatus.UNREACHABLE) == PeerStatus.OK) {
                    // Was previously OK, now gone.
                    log.info("Peer $addr has disappeared: will keep retrying for 24 hours")
                    scheduleRecrawl(addr)
                }
                onDisconnected()
                console.recordConnectFailure()
            }
        }
    }

    private fun queueAddrs(addr: AddressMessage) {
        queueAddrs(addr.addresses.filter { isPeerAddressRoutable(it) }.map { it.toSocketAddress() })
    }

    private fun queueAddrs(sockaddrs: List<InetSocketAddress>) {
        addressQueue.addAll(sockaddrs.map {
            // If we found a peer on the same machine as the cartographer, look up our own hostname to find the public IP
            // instead of publishing localhost.
            if (it.address.isAnyLocalAddress || it.address.isLoopbackAddress) {
                val rs = InetSocketAddress(hostname, it.port)
                log.info("Replacing $it with $rs")
                    rs.toLightweight()
            } else {
                it.toLightweight()
            }
        })
        console.numPendingAddrs = addressQueue.size
    }

    private fun isPeerAddressRoutable(peerAddress: PeerAddress): Boolean {
        val address = peerAddress.addr
        if (address is Inet4Address) {
            val a0 = UnsignedBytes.toInt(address.address[0])
            if (a0 >= 240) // Reserved for future use
                return false
        }
        return true
    }

    private fun onConnect(sockaddr: InetSocketAddress, peer: Peer) {
        connecting.remove(sockaddr)
        console.record(peer.peerVersionMessage)

        val heightDiff = kit.chain().bestChainHeight - peer.bestHeight
        if (heightDiff > 6) {
            log.warn("Peer $peer is behind our block chain by $heightDiff blocks")
            markAs(sockaddr, PeerStatus.BEHIND)
        } else {
            markAsOK(sockaddr, peer)
        }
        // Check up on it again in future to make sure it's still OK/has become OK.
        scheduleRecrawl(sockaddr)
        peer.addr later { addr ->
            queueAddrs(addr)

            if (peer.peerVersionMessage.isGetUTXOsSupported) {
                // Check if it really is, to catch peers that are using the service bit for something else.
                testGetUTXOSupport(peer, sockaddr)
            }

            peer.close()
            onDisconnected()
        }
    }

    private fun testGetUTXOSupport(peer: Peer, sockaddr: InetSocketAddress) {
        try {
            var txhash: Sha256Hash = Sha256Hash.ZERO_HASH
            var outcheck: (TransactionOutput) -> Boolean = { false }
            var height = 0L

            if (params == TestNet3Params.get()) {
                txhash = Sha256Hash.wrap("1c899ae8efd6bd460e517195dc34d2beeca9c5e76ff98af644cf6a28807f86cf")
                outcheck = { it.value == Coin.parseCoin("0.00001") && ScriptPattern.isP2PKH(it.scriptPubKey) && it.scriptPubKey.getToAddress(params).toString() == "mydzGfTrtHx8KnCRu43HfKwYyKjjSo6gUB" }
                height = 314941
            } else if (params == MainNetParams.get()) {
                // For now just assume Satoshi never spends the first block ever mined. There are much
                // more sophisticated and randomized tests possible, but currently we only check for mistakes and
                // not deliberately malicious peers that try to cheat this check.
                txhash = Sha256Hash.wrap("0e3e2357e806b6cdb1f70b54c3a3a17b6714ee1f0e68bebb44a74b1efd512098")
                val pubkey = "0496b538e853519c726a2c91e61ec11600ae1390813a627c66fb8be7947be63c52da7589379515d4e0a604f8141781e62294721166bf621e73a82cbf2342c858ee"
                outcheck = { it.value == Coin.FIFTY_COINS && ScriptPattern.isP2PK(it.scriptPubKey) &&
                             // KT-6587 means we cannot use == as you would expect here.
                             Arrays.equals(it.scriptPubKey.chunks[0].data, BaseEncoding.base16().decode(pubkey.toUpperCase())) }
                height = 1
            }

            val answer = peer.getUTXOs(listOf(TransactionOutPoint(params, 0, txhash))).get(10, TimeUnit.SECONDS)
            val rightHeight = answer.heights[0] == height
            val rightSpentness = answer.hitMap.size == 1 && answer.hitMap[0] == 1.toByte()
            val rightOutput = if (answer.outputs.size == 1) outcheck(answer.outputs[0]) else false
            if (!rightHeight || !rightSpentness || !rightOutput) {
                log.warn("Found peer ${sockaddr} which has the GETUTXO service bit set but didn't answer the test query correctly")
                log.warn("Got $answer")
            } else {
                log.info("Peer $sockaddr is flagged as supporting GETUTXO and passed the test query")
                addrMap[sockaddr] = addrMap[sockaddr]!!.copy(supportsGetUTXO = true)
                db.commit()
            }
        } catch (e: TimeoutException) {
            log.warn("Found peer $sockaddr which has the GETUTXO service bit set but didn't answer quickly enough")
        } catch (e: Exception) {
            log.warn("Crash whilst trying to process getutxo answer from $sockaddr", e)
        }
    }

    private fun onDisconnected() {
        openConnections--
        crawl()
    }

    @Synchronized public fun getSomePeers(size: Int, serviceMask: Long): List<Pair<InetSocketAddress, PeerData>> {
        // Take some items from the head of the list and add them back to the tail, i.e. we loop around.
        val addrs: List<InetSocketAddress> = if (serviceMask == -1L) {
            size.gatherTimes { okPeers.poll() }.filterNotNull()
        } else {
            val matches = okPeers.filter { addrMap[it]!!.serviceBits and serviceMask == serviceMask }.take(size)
            okPeers.removeAll(matches)
            matches
        }
        okPeers.addAll(addrs)
        return addrs.map { it to addrMap[it]!! }
    }

    @Synchronized private fun loadFromDB() {
        // Shuffle the peers because otherwise MapDB can give them back with very close IP ordering.
        val tmp: MutableList<InetSocketAddress> = arrayListOf()
        for ((addr, data) in addrMap) {
            if (data.status == PeerStatus.OK)
                tmp.add(addr)
            if (data.shouldRecrawl())
                scheduleRecrawl(addr)
        }
        Collections.shuffle(tmp)
        okPeers.addAll(tmp)
        log.info("We have ${addrMap.size} IP addresses in our database of which ${okPeers.size} are considered OK")
        console.numOKPeers = okPeers.size
        console.numKnownAddresses = addrMap.size
    }

    private fun scheduleRecrawl(addr: InetSocketAddress) {
        recrawlQueue.add(PendingRecrawl(addr))
    }

    private fun queueAndCrawl(addr: InetSocketAddress) {
        // Running on the wrong thread here, so get back onto the right one.
        // TODO: bcj user thread should probably be a proper scheduled executor
        Threading.USER_THREAD.execute() {
            addressQueue.add(addr.toLightweight())
            crawl()
        }
    }
}
