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

package org.bitcoinj.httpseed

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.BriefLogFormatter
import org.xbill.DNS.Name
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger

@Command(name = "HTTPSeed",
        description = ["A Bitcoin peer to peer network crawler and seed server."])
class BitcoinHTTPSeed : Callable<Int> {

    @Option(names = ["--net"], converter = [NetworkParametersConverter::class], description = ["Bitcoin network to use. Values: main, test, ..."])
    private var params: NetworkParameters = MainNetParams.get()

    @Option(names = ["--dir"], required = true, description = ["Directory to use for storing the private key, the database and more."])
    private var dir = File(System.getProperty("java.io.tmpdir")).toPath()

    @Option(names = ["--http-address"], description = ["Address the HTTP server will bind to."])
    private var httpAddress = null

    @Option(names = ["--http-port"], description = ["Port the HTTP server will bind to. Default: \${DEFAULT-VALUE}"])
    private var httpPort = 8080

    @Option(names = ["--dns-port"], description = ["Port the DNS server will bind to. Default: \${DEFAULT-VALUE}"])
    private var dnsPort = 2053

    @Option(names = ["--hostname"], required = true, description = ["Hostname of the box that is running the crawler."])
    private var hostname = "example.com"

    @Option(names = ["--dnsname"], description = ["Name that the DNS server will respond to (via UDP only)."])
    private var dnsname = null

    @Option(names = ["--log-to-console"], description = ["Log to console rather than to file. Default: \${DEFAULT-VALUE}"])
    private var logToConsole = false

    @Option(names = ["--crawls-per-sec"], description = ["Max connects per second to do. Default: \${DEFAULT-VALUE}"])
    private var crawlsPerSec = 15

    @Option(names = ["--help"], usageHelp = true, description = ["Display this help and exit."])
    private var help = false

    @Suppress("UNREACHABLE_CODE", "ALWAYS_NULL", "SENSELESS_COMPARISON")
    override fun call(): Int {
        setupLogging(dir, logToConsole)

        val crawler = Crawler(dir, params, hostname)
        HttpSeed(if (httpAddress != null) InetAddress.getByName(httpAddress) else null,
                httpPort, "", dir.resolve("privkey"), crawler, params.paymentProtocolId)
        if (dnsname != null)
            DnsServer(Name(if (dnsname!!.endsWith('.')) dnsname else dnsname + '.'), dnsPort, crawler).start()
        crawler.start()

        Runtime.getRuntime().addShutdownHook(Thread { crawler.stop() })
        return 0
    }

    private var loggerPin: Logger? = null
    private fun setupLogging(dir: Path, logToConsole: Boolean) {
        val logger = Logger.getLogger("")
        val handler = if (logToConsole) {
            ConsoleHandler()
        } else {
            FileHandler(dir.resolve("log.txt").toString(), true)
        }
        handler.formatter = BriefLogFormatter()
        logger.removeHandler(logger.handlers[0])
        logger.addHandler(handler)
        loggerPin = logger

        Logger.getLogger("org.bitcoinj").level = Level.SEVERE
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CommandLine(BitcoinHTTPSeed()).execute(*args)
        }
    }
}

class NetworkParametersConverter : CommandLine.ITypeConverter<NetworkParameters> {
    override fun convert(value: String?): NetworkParameters {
        return NetworkParameters.fromPmtProtocolID(value) ?: throw IllegalArgumentException("invalid net parameter")
    }
}
