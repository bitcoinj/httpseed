package net.plan99.bitcoin.cartographer

import kotlin.concurrent.thread
import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.net.*

// A small, simple DNS server.
class DnsServer(private val dnsName: String, private val port: Int, private val crawler: Crawler) {
    private val log = LoggerFactory.getLogger("cartographer.dnsserver")

    public fun start() {
        thread(start = true, daemon = true, name = "DNS UDP", block = {
            val socket = DatagramSocket(port.toInt())
            val inBits = ByteArray(512)
            val inPacket = DatagramPacket(inBits, inBits.size())
            while (true) {
                try {
                    inPacket.setLength(inBits.size())
                    socket.receive(inPacket)
                    val outBits = processMessage(Message(inBits))
                    val outPacket = DatagramPacket(outBits, outBits.size(), inPacket.getSocketAddress())
                    socket.send(outPacket)
                } catch (e: Exception) {
                    log.error("Error handling DNS request", e)
                }
            }
        })
    }

    fun processMessage(message: Message): ByteArray {
        if (message.getHeader().getOpcode() != Opcode.QUERY)
            return errorMessage(message, Rcode.NOTIMP)
        val response = Message(message.getHeader().getID())
        response.getHeader().setFlag(Flags.QR.toInt());
        val ips = crawler.getSomePeers(30, -1)
        for (ip in ips) {
            response.addRecord(ARecord(Name(dnsName), 1, 10, ip.first.getAddress()), Section.ANSWER)
        }
        return response.toWire()
    }

    fun errorMessage(query: Message, code: Int): ByteArray {
        val msg = Message()
        val hdr = query.getHeader()
        hdr.setRcode(code)
        for (i in 0..3) msg.removeAllRecords(i)
        if (code == Rcode.SERVFAIL)
            msg.addRecord(query.getQuestion(), Section.QUESTION)
        msg.setHeader(hdr)
        return msg.toWire()
    }
}