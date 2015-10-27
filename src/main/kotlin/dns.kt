package net.plan99.bitcoin.cartographer

import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.net.*

// A small, simple DNS server.
class DnsServer(private val dnsName: Name, private val port: Int, private val crawler: Crawler) {
    private val log = LoggerFactory.getLogger("cartographer.dnsserver")

    public fun start() {
        thread(start = true, daemon = true, name = "DNS UDP", block = {
            val socket = DatagramSocket(port.toInt())
            val inBits = ByteArray(512)
            val inPacket = DatagramPacket(inBits, inBits.size)
            while (true) {
                try {
                    inPacket.length = inBits.size
                    socket.receive(inPacket)
                    val outBits = processMessage(Message(inBits))
                    val outPacket = DatagramPacket(outBits, outBits.size, inPacket.socketAddress)
                    socket.send(outPacket)
                } catch (e: Throwable) {
                    log.error("Error handling DNS request", e)
                }
            }
        })
    }

    fun processMessage(message: Message): ByteArray {
        val header = message.header
        if (header.opcode != Opcode.QUERY) {
            log.error("Got message with unimplemented opcode {}", header.opcode)
            return errorMessage(message, Rcode.NOTIMP)
        }
        if (header.rcode != Rcode.NOERROR) {
            log.error("Got message with bad rcode: ${header.rcode}")
            return errorMessage(message, Rcode.FORMERR)
        }
        val queryName = message.question.name
        if (queryName != dnsName) {
            log.error("Got query with unrecognised name ${queryName}")
            return errorMessage(message, Rcode.NXDOMAIN)
        }
        val response = Message(header.id)
        if (header.getFlag(Flags.RD.toInt()))
            response.header.setFlag(Flags.RD.toInt())
        response.header.setFlag(Flags.QR.toInt());
        response.header.setFlag(Flags.AA.toInt());
        response.addRecord(message.question, Section.QUESTION)
        val ips = crawler.getSomePeers(30, -1)
        for (ip in ips) {
            val ipaddr = ip.first.address
            val TTL = 60L  // seconds
            try {
                if (ipaddr is Inet4Address)
                    response.addRecord(ARecord(dnsName, 1, TTL, ipaddr), Section.ANSWER)
                else if (ipaddr is Inet6Address)
                    response.addRecord(AAAARecord(dnsName, 1, TTL, ipaddr), Section.ANSWER)
            } catch(e: Exception) {
                log.error("Failed to add record for ${ipaddr}: ${e}")
            }
        }
        return response.toWire()
    }

    fun errorMessage(query: Message, code: Int): ByteArray {
        val msg = Message()
        val hdr = query.header
        hdr.rcode = code
        for (i in 0..3) msg.removeAllRecords(i)
        if (code == Rcode.SERVFAIL)
            msg.addRecord(query.question, Section.QUESTION)
        msg.header = hdr
        return msg.toWire()
    }
}