package net.plan99.bitcoin.cartographer

import com.google.common.collect.Multisets
import com.google.common.collect.HashMultiset
import com.google.common.util.concurrent.RateLimiter
import org.bitcoinj.core.VersionMessage
import java.util.ArrayList
import javax.management.MXBean

@MXBean
public interface ConsoleMXBean {
    public fun getTopUserAgents(): List<String>
    public fun getTotalPauseTimeSecs(): Double

    public val numKnownAddresses: Int
    public val numOKPeers: Int
    public val numConnectFailures: Int
    public val numPendingAddrs: Int
    public val numConnectAttempts: Int

    public var allowedConnectsPerSec: Int
    public var recrawlMinutes: Long

    public fun queueCrawl(ip: String)
    public fun queryStatus(ip: String): String
}

class Console : ConsoleMXBean {
    private val userAgents: HashMultiset<String> = HashMultiset.create()
    private var totalPauseTimeSecs = 0.0

    public var crawler: Crawler? = null

    var connectsRateLimiter: RateLimiter = RateLimiter.create(15.0)
        @Synchronized get
        @Synchronized private set

    // Allow JMX consoles to modify the rate limit on the fly
    override var allowedConnectsPerSec: Int
        @Synchronized get() = connectsRateLimiter.rate.toInt()
        @Synchronized set(value) {
            connectsRateLimiter = RateLimiter.create(value.toDouble())
        }

    override var recrawlMinutes = 30L
        @Synchronized get
        @Synchronized set

    override var numPendingAddrs: Int = 0
        @Synchronized get
        @Synchronized set

    override var numConnectFailures: Int = 0
        @Synchronized get
    override var numConnectAttempts: Int = 0
        @Synchronized get

    @Synchronized public fun record(ver: VersionMessage) {
        userAgents.add(ver.subVer)
    }

    @Synchronized override fun getTopUserAgents(): List<String> = ArrayList(
            Multisets.copyHighestCountFirst(userAgents).entrySet().map { "${it.count}  ${it.element}" }
    ).take(10)

    @Synchronized public fun recordConnectAttempt(): Int = numConnectAttempts++
    @Synchronized public fun recordConnectFailure(): Int = numConnectFailures++

    @Synchronized fun recordPauseTime(pauseTimeSecs: Double) {
        totalPauseTimeSecs += pauseTimeSecs
    }
    @Synchronized override fun getTotalPauseTimeSecs(): Double = totalPauseTimeSecs

    override var numKnownAddresses: Int = 0
        @Synchronized get
        @Synchronized public set
    override var numOKPeers: Int = 0
        @Synchronized get
        @Synchronized public set

    override fun queueCrawl(ip: String) {
        crawler!!.attemptConnect(parseIPAndPort(ip))
    }

    override fun queryStatus(ip: String): String = crawler!!.addrMap[parseIPAndPort(ip)]?.status?.toString() ?: "Unknown"
}