package net.plan99.bitcoin.crawler

import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.kits.WalletAppKit
import java.nio.file.*
import java.util.logging.FileHandler
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.core.PeerAddress
import org.bitcoinj.core.Peer
import org.bitcoinj.net.ClientConnectionManager
import org.bitcoinj.net.NioClientManager
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.bitcoinj.core.VersionMessage
import java.util.Collections
import java.net.InetSocketAddress
import java.net.Inet6Address
import java.util.logging.Level
import com.google.common.util.concurrent.RateLimiter
import java.util.Queue
import java.util.concurrent.LinkedBlockingQueue
import net.jcip.annotations.GuardedBy
import java.util.LinkedHashMap
import java.util.LinkedList
import java.util.HashMap
import java.util.ArrayList
import org.mapdb.DBMaker
import java.io.Serializable
import java.util.HashSet
import java.time.Instant
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.bitcoinj.utils.Threading
import java.time.temporal.TemporalAmount
import java.time.Duration

enum class PeerStatus {
    UNTESTED
    UNREACHABLE
    OK
}

data class PeerData(val status: PeerStatus, val serviceBits: Long, val lastCrawlTime: Instant, val lastSuccessTime: Instant? = null) : Serializable {
    fun isTimeToRecrawl(recrawlMinutes: Long): Boolean {
        val ago = Instant.now().minusSeconds(recrawlMinutes * 60)
        val before = this.lastCrawlTime.isBefore(ago)
        return before
    }

    // We recrawl nodes that are currently up to check they're still alive, or nodes which *were* up within the last day
    // but have disappeared to see if they come back.
    fun shouldRecrawl() = status == PeerStatus.OK ||
                            (status == PeerStatus.UNREACHABLE &&
                             lastSuccessTime != null &&
                             lastSuccessTime isAfter Instant.now() - Duration.ofDays(1))
}

// Crawler engine
class Crawler(private val console: Console, private val workingDir: Path, private val params: NetworkParameters) {
    private val log: Logger = LoggerFactory.getLogger("crawler.engine")

    private val db = DBMaker.newFileDB(workingDir.resolve("crawlerdb").toFile()).make()
    private val peerMap: MutableMap<InetSocketAddress, PeerData> = db.getHashMap("addrToStatus")
    [GuardedBy("this")] private val okPeers: LinkedList<InetSocketAddress> = LinkedList()

    private val connecting: MutableSet<InetSocketAddress> = Collections.synchronizedSet(HashSet())

    private val ccm = NioClientManager()
    private val verMsg: VersionMessage = VersionMessage(params, -1)

    // Rate limiting
    private var openConnections = 0
    private val maxConnections = 1023
    private val addressQueue = LinkedBlockingQueue<InetSocketAddress>()

    // Recrawl thread
    private val recrawlExecutor = ScheduledThreadPoolExecutor(1)

    public fun start() {
        populateOKPeers()    // Load from DB
        scheduleRecrawlsFromDB()

        verMsg.appendToSubVer("Crawler", "1.0", null)

        // We use the low level networking API to crawl, because PeerGroup does things like backoff/retry/etc which we don't want.
        ccm.startAsync().awaitRunning()

        // We use a regular WAK setup to learn about the state of the network but not to crawl it.
        val kit = WalletAppKit(params, workingDir.toFile(), "crawler")
        kit.setUserAgent("Crawler", "1.0")
        // kit.setPeerNodes(PeerAddress(InetSocketAddress("vinumeris.com", params.getPort())))
        log.info("Waiting for block chain headers to sync ...")
        kit.startAsync().awaitRunning()
        log.info("Chain synced, querying initial addresses")
        val peer = kit.peerGroup().waitForPeers(1).get()[0]
        peer.getAddr() later { addr ->
            log.info("Initial addresses acquired, starting crawl")
            kit.stopAsync()
            addressQueue.addAll(addr.getAddresses().map { it.getSocketAddress() })
            crawl()
        }
    }

    fun crawl() {
        while (openConnections < maxConnections) {
            val p = addressQueue.poll()
            if (p == null) break
            // Some addr messages have bogus port values in them; ignore.
            if (p.getPort() == 0) continue

            if (connecting.contains(p)) continue

            val data = peerMap.get(p)
            val currentStatus: PeerStatus? = data?.status
            var doConnect = false
            if (currentStatus == null) {
                // Not seen this address before and not already probing it
                peerMap.put(p, PeerData(PeerStatus.UNTESTED, 0, Instant.now()))
                db.commit()
                doConnect = true
            } else if (currentStatus == PeerStatus.OK) {
                doConnect = data!!.isTimeToRecrawl(console.recrawlMinutes)
            } else if (currentStatus == PeerStatus.UNREACHABLE) {
                // Recrawl an unreachable host if it was OK within the last 24 hours and we reached the recrawl time
                doConnect = data!!.shouldRecrawl() && data.isTimeToRecrawl(console.recrawlMinutes)
            }

            if (doConnect)
                attemptConnect(p)
        }
    }

    private fun markAsUnreachable(addr: InetSocketAddress): PeerStatus {
        val cur = peerMap.get(addr)!!
        val newData = cur.copy(status = PeerStatus.UNREACHABLE, lastCrawlTime = Instant.now())
        peerMap.put(addr, newData)
        db.commit()
        synchronized(this) { okPeers.remove(addr) }
        return cur.status
    }

    private fun markAsOK(addr: InetSocketAddress, peer: Peer) {
        val peerData = peerMap.get(addr)
        if (peerData != null && peerData.status == PeerStatus.UNREACHABLE && peerData.lastSuccessTime != null)
            log.info("Peer ${addr} came back from the dead")
        var newData = PeerData(
                status = PeerStatus.OK,
                lastCrawlTime = Instant.now(),
                serviceBits = peer.getPeerVersionMessage().localServices,
                lastSuccessTime = Instant.now()
        )
        peerMap.put(addr, newData)
        db.commit()
        synchronized(this) { okPeers.add(addr) }
        scheduleRecrawl(addr)
    }

    fun attemptConnect(addr: InetSocketAddress) {
        connecting.add(addr)
        val peer = Peer(params, verMsg, null, PeerAddress(addr))
        peer.getVersionHandshakeFuture() later { peer ->
            // Possibly pause a moment to stay within our connects/sec budget.
            console.successfulConnectsRateLimiter.acquire()
            onConnect(addr, peer)
        }
        openConnections++
        console.recordConnectAttempt()
        ccm.openConnection(addr, peer) later { sockaddr, error ->
            if (error != null) {
                if (markAsUnreachable(addr) == PeerStatus.OK) {
                    // Was previously OK, now gone.
                    log.info("Peer ${addr} has disappeared: will keep retrying for 24 hours")
                    scheduleRecrawl(addr)
                }
                onDisconnected()
            }
        }
    }

    private fun onConnect(sockaddr: InetSocketAddress, peer: Peer) {
        connecting.remove(sockaddr)
        markAsOK(sockaddr, peer)
        console.record(peer.getPeerVersionMessage())
        peer.getAddr() later { addr ->
            addressQueue.addAll(addr.getAddresses().map { it.toSocketAddress() })
            peer.close()
            onDisconnected()
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
            val matches = okPeers.filter { peerMap.get(it)!!.serviceBits and serviceMask == serviceMask }.take(size)
            okPeers.removeAll(matches)
            matches
        }
        okPeers.addAll(addrs)
        return addrs.map { it to peerMap.get(it)!! }
    }

    synchronized private fun populateOKPeers() {
        // Shuffle the peers because otherwise MapDB can give them back with very close IP ordering.
        okPeers addAll peerMap.filter { it.getValue().status == PeerStatus.OK }.map { it.getKey() }.toArrayList().shuffle()
        log.info("We have ${peerMap.size()} peers in our database of which ${okPeers.size()} are considered OK")
    }

    private fun scheduleRecrawl(addr: InetSocketAddress) {
        recrawlExecutor.schedule({
            // Running on the wrong thread here, so get back onto the right one.
            // TODO: bcj user thread should probably be a proper scheduled executor
            Threading.USER_THREAD.execute() {
                addressQueue.add(addr)
                crawl()
            }
        }, console.recrawlMinutes, TimeUnit.MINUTES)
    }

    private fun scheduleRecrawlsFromDB() {
        // Recrawl peers in the db that are either considered OK, or have disappeared recently
        peerMap.filter { it.getValue().shouldRecrawl() }.map { it.getKey() }.forEach { scheduleRecrawl(it) }
    }
}
