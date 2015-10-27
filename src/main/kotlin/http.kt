package net.plan99.bitcoin.cartographer

import com.sun.net.httpserver.*
import com.googlecode.protobuf.format.*
import com.google.common.base.Splitter
import com.google.common.io.BaseEncoding
import java.net.*
import java.nio.file.*
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream
import org.threeten.bp.Instant
import java.util.zip.GZIPOutputStream
import org.bitcoinj.core.*
import org.slf4j.LoggerFactory
import org.bitcoin.crawler.PeerSeedProtos
import org.bitcoinj.utils.Threading

class HttpSeed(address: InetAddress?, port: Int, baseUrlPath: String, privkeyPath: Path, private val crawler: Crawler, private val netName: String) {
    private val log = LoggerFactory.getLogger("cartographer.http")
    private val server: HttpServer
    private val privkey: ECKey

    init {
        if (!Files.exists(privkeyPath)) {
            privkey = ECKey()
            Files.write(privkeyPath, (privkey.privateKeyAsHex + "\n").toByteArray())
            log.info("Created fresh private key, public is ${privkey.publicKeyAsHex}")
        } else {
            val str = Files.readAllLines(privkeyPath, StandardCharsets.UTF_8)[0].trim()
            privkey = ECKey.fromPrivate(BaseEncoding.base16().decode(str.toUpperCase()))
            log.info("Using public key: ${privkey.publicKeyAsHex}")
        }
        val inetSocketAddress = InetSocketAddress(address, port)
        log.info("Binding HTTP server to $inetSocketAddress, context path is ${if (baseUrlPath.isEmpty()) "/" else baseUrlPath}")
        server = HttpServer.create(inetSocketAddress, 0)
        serve("GET", "$baseUrlPath/peers", { handlePeersRequest(it) })
        serve("GET", "$baseUrlPath/lookup", { handleLookupRequest(it) })
        serve("GET", "$baseUrlPath/recrawls", { handleRecrawlsRequest(it) })
        serve("GET", "$baseUrlPath/force", { handleForceRequest(it) })
        server.start()
    }

    class BadRequestException : Exception()

    private fun serve(method: String, path: String, handler: HttpSeed.(HttpExchange) -> Unit) {
        server.createContext(path, object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                try {
                    if (exchange.requestMethod != method) {
                        exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1)
                        exchange.close()
                        return
                    }
                    handler(exchange)
                } catch (e: BadRequestException) {
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, -1)
                    exchange.close()
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1)
                    exchange.close()
                }
            }
        })
    }

    private fun handleRecrawlsRequest(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0)
        exchange.responseBody.writer().use {
            var i = 0
            for (pending in crawler.snapshotRecrawlQueue()) {
                it.write("$pending\n")
                i++
            }
        }
        exchange.close()
    }

    // Diagnostic query for status of individual peer.
    private fun handleLookupRequest(exchange: HttpExchange) {
        val query: String = exchange.requestURI.query ?: throw BadRequestException()
        val ip = if (query.contains(":")) parseIPAndPort(query) else InetSocketAddress(query, crawler.params.port)
        val response = crawler.addrMap[ip]?.toString() ?: "Unknown"
        val bits = response.toByteArray()
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bits.size.toLong())
        exchange.responseBody.write(bits)
        exchange.close()
    }

    private fun handleForceRequest(exchange: HttpExchange) {
        val query: String = exchange.requestURI.query ?: throw BadRequestException()
        val ip = if (query.contains(":")) parseIPAndPort(query) else InetSocketAddress(query, crawler.params.port)
        log.info("Forcing recrawl for $ip")
        Threading.USER_THREAD.execute() {
            crawler.attemptConnect(ip)
        }
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1)
        exchange.close()
    }

    // The main seed query.
    private fun handlePeersRequest(exchange: HttpExchange) {
        val query: String? = exchange.requestURI.query
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
                .setTimestamp(Instant.now().epochSecond)
                .setNet(netName)
                .build()

        val noCache = params.containsKey("nocache")

        val path = exchange.requestURI.path
        when {
            path.endsWith(".html") -> respond(exchange, protoToHTML(msg), "text/html; charset=UTF-8", noCache)
            path.endsWith(".json") -> respond(exchange, protoToJSON(msg), "application/json", noCache)
            path.endsWith(".xml") ->  respond(exchange, protoToXML(msg), "text/xml", noCache)
            path.endsWith(".txt") ->  respond(exchange, protoToText(msg), "text/plain", noCache)

            // Default format is signed protobuf
            else -> respond(exchange, signSerializeAndCompress(msg), "application/octet-stream", noCache)
        }
    }

    fun protoToHTML(msg: PeerSeedProtos.PeerSeeds) = HtmlFormat.printToString(msg).toByteArray()
    fun protoToJSON(msg: PeerSeedProtos.PeerSeeds) = JsonFormat.printToString(msg).toByteArray()
    fun protoToXML(msg: PeerSeedProtos.PeerSeeds)  = XmlFormat.printToString(msg).toByteArray()
    fun protoToText(msg: PeerSeedProtos.PeerSeeds) = msg.seedList.map { it.ipAddress }.joinToString(",").toByteArray()

    fun dataToProto(data: Pair<InetSocketAddress, PeerData>): PeerSeedProtos.PeerSeedData {
        val builder = PeerSeedProtos.PeerSeedData.newBuilder()
        builder.setIpAddress(data.first.address.toString().substring(1))
        builder.setPort(data.first.port)
        builder.setServices(data.second.serviceBits.toInt())
        return builder.build()
    }

    fun signSerializeAndCompress(msg: PeerSeedProtos.PeerSeeds): ByteArray {
        val wrapper = PeerSeedProtos.SignedPeerSeeds.newBuilder()
        val bits = msg.toByteString()
        wrapper.setPeerSeeds(bits)
        wrapper.setPubkey(privkey.pubKey.toByteString())
        wrapper.setSignature(privkey.sign(Sha256Hash.of(bits.toByteArray())).encodeToDER().toByteString())
        val baos = ByteArrayOutputStream()
        val zip = GZIPOutputStream(baos)
        wrapper.build().writeDelimitedTo(zip)
        zip.finish()
        return baos.toByteArray()
    }

    fun respond(exchange: HttpExchange, bits: ByteArray, mimeType: String, noCache: Boolean) {
        exchange.responseHeaders.add("Content-Type", mimeType)
        if (!noCache) {
            // Allow ISPs and so on to cache results: less bandwidth usage, more privacy -> it's a win. The signatures
            // on protobuf versions should prevent caches from tampering with the data. We can bump up the cache time in
            // future once the debugging/testing phase is over.
            exchange.responseHeaders.add("Cache-Control", "no-transform,public,max-age=90")
        }
        exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, bits.size.toLong())
        exchange.responseBody.write(bits)
        exchange.close()
    }
}