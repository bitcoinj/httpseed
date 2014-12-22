package net.plan99.bitcoin.cartographer

import com.sun.net.httpserver.*
import com.googlecode.protobuf.format.*
import com.google.common.base.Splitter
import com.google.common.io.BaseEncoding
import java.net.*
import java.nio.file.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import org.bitcoinj.core.*
import org.slf4j.LoggerFactory
import org.bitcoin.crawler.PeerSeedProtos

class HTTPServer(port: Int, baseUrlPath: String, privkeyPath: Path, private val crawler: Crawler, private val netName: String) {
    private val log = LoggerFactory.getLogger("cartographer.http")
    private val server: HttpServer
    private val privkey: ECKey

    {
        if (!Files.exists(privkeyPath)) {
            privkey = ECKey()
            Files.write(privkeyPath, (privkey.getPrivateKeyAsHex() + "\n").toByteArray())
            log.info("Created fresh private key, public is ${privkey.getPublicKeyAsHex()}")
        } else {
            val str = Files.readAllLines(privkeyPath)[0].trim()
            privkey = ECKey.fromPrivate(BaseEncoding.base16().decode(str.toUpperCase()))
            log.info("Using public key: ${privkey.getPublicKeyAsHex()}")
        }
        server = HttpServer.create(InetSocketAddress(port), 0)
        server.createContext("${baseUrlPath}/peers", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                try {
                    process(exchange)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exchange.sendResponseHeaders(500, -1)
                    exchange.close()
                }
            }
        })
        server.start()
    }

    fun process(exchange: HttpExchange) {
        // Forbid everything except GET
        if (exchange.getRequestMethod() != "GET") {
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1)
            exchange.close()
            return
        }

        val query: String? = exchange.getRequestURI().getQuery()
        val params: Map<String, String> = if (query != null) Splitter.on('&').trimResults().withKeyValueSeparator("=").split(query) else mapOf()
        val serviceMask = params.get("srvmask")?.toLong()?.and(0xFFFFFF)
        val getutxo = params.get("getutxo") == "true"
        var data = crawler.getSomePeers(30, serviceMask ?: -1)
        if (getutxo && serviceMask != null && (serviceMask and 3L) == 3L) {
            // Filter response to remove broken peers.
            data = data.filter { it.second.supportsGetUTXO }
        }
        val protos = data.map { dataToProto(it) }
        val msg = PeerSeedProtos.PeerSeeds.newBuilder()
                .addAllSeed(protos)
                .setTimestamp(Instant.now().getEpochSecond())
                .setNet(netName)
                .build()

        val path = exchange.getRequestURI().getPath()
        when {
            path.endsWith(".html") -> respond(exchange, protoToHTML(msg), "text/html; charset=UTF-8")
            path.endsWith(".json") -> respond(exchange, protoToJSON(msg), "application/json")
            path.endsWith(".xml") ->  respond(exchange, protoToXML(msg), "text/xml")

            // Default format is signed protobuf
            else -> respond(exchange, signSerializeAndCompress(msg), "application/octet-stream")
        }
    }

    fun protoToHTML(msg: PeerSeedProtos.PeerSeeds) = HtmlFormat.printToString(msg).toByteArray(Charsets.UTF_8)
    fun protoToJSON(msg: PeerSeedProtos.PeerSeeds) = JsonFormat.printToString(msg).toByteArray(Charsets.UTF_8)
    fun protoToXML(msg: PeerSeedProtos.PeerSeeds) = XmlFormat.printToString(msg).toByteArray(Charsets.UTF_8)

    fun dataToProto(data: Pair<InetSocketAddress, PeerData>): PeerSeedProtos.PeerSeedData {
        val builder = PeerSeedProtos.PeerSeedData.newBuilder()
        builder.setIpAddress(data.first.getAddress().toString().substring(1))
        builder.setPort(data.first.getPort())
        builder.setServices(data.second.serviceBits.toInt())
        return builder.build()
    }

    fun signSerializeAndCompress(msg: PeerSeedProtos.PeerSeeds): ByteArray {
        val wrapper = PeerSeedProtos.SignedPeerSeeds.newBuilder()
        val bits = msg.toByteString()
        wrapper.setPeerSeeds(bits)
        wrapper.setPubkey(privkey.getPubKey().toByteString())
        wrapper.setSignature(privkey.sign(Sha256Hash.create(bits.toByteArray())).encodeToDER().toByteString())
        val baos = ByteArrayOutputStream()
        val zip = GZIPOutputStream(baos)
        wrapper.build().writeDelimitedTo(zip)
        zip.finish()
        return baos.toByteArray()
    }

    fun respond(exchange: HttpExchange, bits: ByteArray, mimeType: String) {
        exchange.getResponseHeaders().add("Content-Type", mimeType);
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bits.size().toLong())
        exchange.getResponseBody().write(bits)
        exchange.close()
    }
}