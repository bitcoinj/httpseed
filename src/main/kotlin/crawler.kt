package net.plan99.bitcoin.cartographer

import org.bitcoinj.params.*
import org.bitcoinj.core.*
import org.bitcoinj.kits.WalletAppKit
import org.bitcoinj.net.NioClientManager
import org.bitcoinj.utils.Threading
import org.mapdb.DBMaker
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.*
import java.util.concurrent.*
import java.util.*
import java.io.Serializable
import java.time.*
import java.net.InetSocketAddress
import net.jcip.annotations.GuardedBy
import com.google.common.io.BaseEncoding

enum class PeerStatus {
    UNTESTED
    UNREACHABLE
    BEHIND   // Not caught up with the block chain
    OK
}

data class PeerData(val status: PeerStatus, val serviceBits: Long, val lastCrawlTime: Instant, val lastSuccessTime: Instant? = null, val supportsGetUTXO: Boolean = false) : Serializable {
    fun isTimeToRecrawl(recrawlMinutes: Long): Boolean {
        val ago = Instant.now().minusSeconds(recrawlMinutes * 60)
        val before = this.lastCrawlTime.isBefore(ago)
        return before
    }

    // We recrawl nodes that are currently up to check they're still alive, or nodes which *were* up within the last day
    // but have disappeared to see if they come back, or nodes that were behind when we last checked them.
    fun shouldRecrawl() = status == PeerStatus.OK || status == PeerStatus.BEHIND ||
                            (status == PeerStatus.UNREACHABLE &&
                             lastSuccessTime != null && lastSuccessTime isAfter Instant.now() - Duration.ofDays(1))
}

// Crawler engine
class Crawler(private val console: Console, private val workingDir: Path, private val params: NetworkParameters, private val hostname: String) {
    private val log: Logger = LoggerFactory.getLogger("cartographer.engine")

    private val kit = WalletAppKit(params, workingDir.toFile(), "cartographer")
    private val db = DBMaker.newFileDB(workingDir.resolve("crawlerdb").toFile()).make()
    public val addrMap: MutableMap<InetSocketAddress, PeerData> = db.getHashMap("addrToStatus")
    [GuardedBy("this")] private val okPeers: LinkedList<InetSocketAddress> = LinkedList()

    private val connecting: MutableSet<InetSocketAddress> = Collections.synchronizedSet(HashSet())

    private val ccm = NioClientManager()
    private val verMsg: VersionMessage = VersionMessage(params, -1)

    // Rate limiting
    private var openConnections = 0
    private val maxConnections = 1023
    private val addressQueue = HashSet<InetSocketAddress>()

    // Recrawl thread
    private val recrawlExecutor = ScheduledThreadPoolExecutor(1)

    public fun start() {
        console.crawler = this

        populateOKPeers()    // Load from DB
        scheduleRecrawlsFromDB()

        verMsg.appendToSubVer("Cartographer", "1.0", null)

        // We use the low level networking API to crawl, because PeerGroup does things like backoff/retry/etc which we don't want.
        ccm.startAsync().awaitRunning()

        // We use a regular WAK setup to learn about the state of the network but not to crawl it.
        kit.setUserAgent("Cartographer", "1.0")
        // kit.setPeerNodes(PeerAddress(InetSocketAddress("vinumeris.com", params.getPort())))
        log.info("Waiting for block chain headers to sync ...")
        kit.startAsync().awaitRunning()
        log.info("Chain synced, querying initial addresses")
        val peer = kit.peerGroup().waitForPeers(1).get()[0]

        // When we receive an addr broadcast from our long-term network connections, queue up the addresses for crawling.
        kit.peerGroup().addEventListener(object : AbstractPeerEventListener() {
            override fun onPreMessageReceived(peer: Peer, m: Message): Message {
                if (m is AddressMessage) {
                    Threading.USER_THREAD execute {
                        val fresh = m.getAddresses() filterNot { addrMap.containsKey(it.getSocketAddress()) or addressQueue.contains(it.getSocketAddress()) }
                        if (fresh.isNotEmpty()) {
                            log.info("Got ${fresh.size()} new address(es) from ${peer}" + if (fresh.size() < 10) ": " + fresh.joinToString(",") else "")
                            queueAddrs(m)
                            crawl()
                        }
                    }
                }
                return m
            }
        }, Threading.SAME_THREAD)

        if (okPeers.isEmpty()) {
            // First run: request some addresses. Response will be handled by the event listener above.
            peer.getAddr()
        } else {
            // Pick some peers that were considered OK on the last run and recrawl them immediately to kick things off again.
            log.info("Kicking off crawl with some peers from previous run")
            console.numOKPeers = okPeers.size()
            Threading.USER_THREAD.execute() {
                okPeers.take(20) forEach { attemptConnect(it) }
            }
        }
    }

    fun crawl() {
        while (openConnections < maxConnections) {
            val p = addressQueue.firstOrNull()
            if (p == null) break
            addressQueue.remove(p)
            // Some addr messages have bogus port values in them; ignore.
            if (p.getPort() == 0) continue

            if (connecting.contains(p)) continue

            val data = addrMap[p]
            var doConnect = if (data == null) {
                // Not seen this address before and not already probing it
                addrMap[p] = PeerData(PeerStatus.UNTESTED, 0, Instant.now())
                console.numKnownAddresses = addrMap.size()
                db.commit()
                true
            } else {
                data.shouldRecrawl() && data.isTimeToRecrawl(console.recrawlMinutes)
            }

            if (doConnect)
                attemptConnect(p)
        }
    }

    private fun markAs(addr: InetSocketAddress, status: PeerStatus): PeerStatus {
        val cur = addrMap[addr]!!
        val newData = cur.copy(status = status, lastCrawlTime = Instant.now())
        addrMap[addr] = newData
        console.numKnownAddresses = addrMap.size()
        db.commit()
        synchronized(this) {
            okPeers.remove(addr)
            console.numOKPeers = okPeers.size()
        }
        return cur.status
    }

    private fun markAsOK(addr: InetSocketAddress, peer: Peer) {
        val peerData: PeerData? = addrMap[addr]
        val oldStatus = peerData?.status
        if (oldStatus == PeerStatus.UNREACHABLE && peerData!!.lastSuccessTime != null)
            log.info("Peer ${addr} came back from the dead")
        var newData = PeerData(
                status = PeerStatus.OK,
                lastCrawlTime = Instant.now(),
                serviceBits = peer.getPeerVersionMessage().localServices,
                lastSuccessTime = Instant.now()
        )
        addrMap[addr] = newData
        console.numKnownAddresses = addrMap.size()
        db.commit()

        // We might have recrawled an OK peer if forced via JMX.
        if (oldStatus != PeerStatus.OK) {
            synchronized(this) {
                okPeers.add(addr)
                console.numOKPeers = okPeers.size()
            }
            scheduleRecrawl(addr)
        }
    }

    fun attemptConnect(addr: InetSocketAddress) {
        connecting.add(addr)
        val peer = Peer(params, verMsg, null, PeerAddress(addr))
        peer.getVersionHandshakeFuture() later { peer ->
            onConnect(addr, peer)
        }
        // Possibly pause a moment to stay within our connects/sec budget.
        val pauseTime = console.connectsRateLimiter.acquire()
        console.recordPauseTime(pauseTime)
        openConnections++
        console.recordConnectAttempt()
        ccm.openConnection(addr, peer) later { sockaddr, error ->
            if (error != null) {
                if (markAs(addr, PeerStatus.UNREACHABLE) == PeerStatus.OK) {
                    // Was previously OK, now gone.
                    log.info("Peer ${addr} has disappeared: will keep retrying for 24 hours")
                    scheduleRecrawl(addr)
                }
                onDisconnected()
            }
        }
    }

    private fun queueAddrs(addr: AddressMessage) {
        addressQueue.addAll(addr.getAddresses() map {
            val sockaddr = it.toSocketAddress()
            // If we found a peer on the same machine as the cartographer, look up our own hostname to find the public IP
            // instead of publishing localhost.
            if (sockaddr.getAddress().isAnyLocalAddress() || sockaddr.getAddress().isLoopbackAddress()) {
                val rs = InetSocketAddress(hostname, sockaddr.getPort())
                log.info("Replacing ${sockaddr} with ${rs}")
                rs
            } else {
                sockaddr
            }
        })
    }

    private fun onConnect(sockaddr: InetSocketAddress, peer: Peer) {
        connecting.remove(sockaddr)
        console.record(peer.getPeerVersionMessage())

        val heightDiff = kit.chain().getBestChainHeight() - peer.getBestHeight()
        if (heightDiff > 6) {
            log.warn("Peer ${peer} is behind our block chain by ${heightDiff} blocks")
            markAs(sockaddr, PeerStatus.BEHIND)
        } else {
            markAsOK(sockaddr, peer)
        }
        peer.getAddr() later { addr ->
            queueAddrs(addr)

            if (peer.getPeerVersionMessage().isGetUTXOsSupported()) {
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
                txhash = Sha256Hash("1c899ae8efd6bd460e517195dc34d2beeca9c5e76ff98af644cf6a28807f86cf")
                outcheck = { it.getValue() == Coin.parseCoin("0.00001") && it.getScriptPubKey().isSentToAddress() && it.getScriptPubKey().getToAddress(params).toString() == "mydzGfTrtHx8KnCRu43HfKwYyKjjSo6gUB" }
                height = 314941
            } else if (params == MainNetParams.get()) {
                // For now just assume Satoshi never spends the first block ever mined. There are much
                // more sophisticated and randomized tests possible, but currently we only check for mistakes and
                // not deliberately malicious peers that try to cheat this check.
                txhash = Sha256Hash("0e3e2357e806b6cdb1f70b54c3a3a17b6714ee1f0e68bebb44a74b1efd512098")
                outcheck = { it.getValue() == Coin.FIFTY_COINS && it.getScriptPubKey().isSentToRawPubKey() && it.getScriptPubKey().getChunks()[0].data == BaseEncoding.base16().decode("0496b538e853519c726a2c91e61ec11600ae1390813a627c66fb8be7947be63c52da7589379515d4e0a604f8141781e62294721166bf621e73a82cbf2342c858ee") }
                height = 1
            }

            val answer = peer.getUTXOs(listOf(TransactionOutPoint(params, 0, txhash))).get(10, TimeUnit.SECONDS)
            val rightHeight = answer.getHeights()[0] == height
            val rightSpentness = answer.getHitMap().size() == 1 && answer.getHitMap()[0] == 1.toByte()
            val rightOutput = if (answer.getOutputs().size() == 1) outcheck(answer.getOutputs()[0]) else false
            if (!rightHeight || !rightSpentness || !rightOutput) {
                log.warn("Found peer ${sockaddr} which has the GETUTXO service bit set but didn't answer the test query correctly")
                log.warn("Got ${answer}")
            } else {
                log.info("Peer ${sockaddr} is flagged as supporting GETUTXO and passed the test query")
                addrMap[sockaddr] = addrMap[sockaddr]!!.copy(supportsGetUTXO = true)
                db.commit()
            }
        } catch (e: TimeoutException) {
            log.warn("Found peer ${sockaddr} which has the GETUTXO service bit set but didn't answer quickly enough")
        } catch (e: Exception) {
            log.warn("Crash whilst trying to process getutxo answer from ${sockaddr}", e)
        }
    }

    private fun onDisconnected() {
        openConnections--
        crawl()
    }

    synchronized public fun getSomePeers(size: Int, serviceMask: Long): List<Pair<InetSocketAddress, PeerData>> {
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

    synchronized private fun populateOKPeers() {
        // Shuffle the peers because otherwise MapDB can give them back with very close IP ordering.
        okPeers addAll addrMap.filter { it.getValue().status == PeerStatus.OK }.map { it.getKey() }.toArrayList().shuffle()
        log.info("We have ${addrMap.size()} IP addresses in our database of which ${okPeers.size()} are considered OK")
        console.numOKPeers = okPeers.size()
        console.numKnownAddresses = addrMap.size()
    }

    private fun scheduleRecrawl(addr: InetSocketAddress) {
        recrawlExecutor.schedule({
            queueAndCrawl(addr)
        }, console.recrawlMinutes, TimeUnit.MINUTES)
    }

    private fun queueAndCrawl(addr: InetSocketAddress) {
        // Running on the wrong thread here, so get back onto the right one.
        // TODO: bcj user thread should probably be a proper scheduled executor
        Threading.USER_THREAD.execute() {
            addressQueue.add(addr)
            crawl()
        }
    }

    private fun scheduleRecrawlsFromDB() {
        // Recrawl peers in the db that are either considered OK, or have disappeared recently
        addrMap filter { it.getValue().shouldRecrawl() } map { it.getKey() } forEach { scheduleRecrawl(it) }
    }
}
