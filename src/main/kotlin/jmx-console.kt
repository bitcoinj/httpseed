package net.plan99.bitcoin.crawler

import com.google.common.collect.Multiset
import com.google.common.collect.Multisets
import com.google.common.collect.HashMultiset
import org.bitcoinj.core.VersionMessage
import com.google.common.collect.Collections2
import java.util.ArrayList
import kotlin.concurrent.thread
import com.google.common.util.concurrent.RateLimiter
import javax.management.MXBean
import java.util.LinkedList
import java.net.InetAddress
import java.net.InetSocketAddress
import com.google.common.net.HostAndPort

[MXBean]
public trait ConsoleMXBean {
    public fun getConnectAttempts(): Int
    public fun getTopUserAgents(): List<String>
    public fun getTotalPauseTimeSecs(): Double
    public val numKnownAddresses: Int
    public val numOKPeers: Int

    public var allowedSuccessfulConnectsPerSec: Int
    public var recrawlMinutes: Long

    public fun queueCrawl(ip: String)
}

class Console : ConsoleMXBean {
    private val userAgents: HashMultiset<String> = HashMultiset.create()
    private var connects: Int = 0
    private var totalPauseTimeSecs = 0.0

    public var crawler: Crawler? = null

    var successfulConnectsRateLimiter: RateLimiter = RateLimiter.create(20.0)
        [synchronized] get
        [synchronized] private set

    // Allow JMX consoles to modify the rate limit on the fly
    override var allowedSuccessfulConnectsPerSec: Int = successfulConnectsRateLimiter.getRate().toInt()
        [synchronized] get
        [synchronized] set(value) {
            successfulConnectsRateLimiter = RateLimiter.create(value.toDouble())
        }

    override var recrawlMinutes = 30L
        [synchronized] get
        [synchronized] set

    synchronized public fun record(ver: VersionMessage) {
        userAgents.add(ver.subVer)
    }

    synchronized override fun getTopUserAgents(): List<String> = ArrayList(
            Multisets.copyHighestCountFirst(userAgents).entrySet().map { "${it.getCount()}  ${it.getElement()}" }
    ).take(10)

    synchronized public fun recordConnectAttempt(): Int = connects++
    synchronized override fun getConnectAttempts() = connects

    synchronized fun recordPauseTime(pauseTimeSecs: Double) {
        totalPauseTimeSecs += pauseTimeSecs
    }
    synchronized override fun getTotalPauseTimeSecs(): Double = totalPauseTimeSecs

    override var numKnownAddresses: Int = 0
        [synchronized] get
        [synchronized] public set
    override var numOKPeers: Int = 0
        [synchronized] get
        [synchronized] public set

    override fun queueCrawl(ip: String) {
        val hostAndPort = HostAndPort.fromString(ip)
        val sockaddr = InetSocketAddress(hostAndPort.getHostText(), hostAndPort.getPort())
        crawler!!.attemptConnect(sockaddr)
    }
}