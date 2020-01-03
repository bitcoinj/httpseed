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

import joptsimple.OptionException
import joptsimple.OptionParser
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.utils.BriefLogFormatter
import org.xbill.DNS.Name
import java.net.InetAddress
import java.nio.file.Path
import java.nio.file.Paths
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger

public class BitcoinHTTPSeed {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val parser = OptionParser()
            val netArg = parser.accepts("net").withRequiredArg().defaultsTo("main")
            val dirArg = parser.accepts("dir").withRequiredArg().required()
            val httpAddress = parser.accepts("http-address").withRequiredArg()
            val httpPort = parser.accepts("http-port").withRequiredArg().defaultsTo("8080")
            val dnsPort = parser.accepts("dns-port").withRequiredArg().defaultsTo("2053")
            val hostname = parser.accepts("hostname").withRequiredArg().required()
            val dnsname = parser.accepts("dnsname").withRequiredArg()
            val logToConsole = parser.accepts("log-to-console")
            val crawlsPerSec = parser.accepts("crawls-per-sec").withRequiredArg().ofType(Int::class.java).defaultsTo(15)
            val help = parser.accepts("help").forHelp()

            val options = try {
                parser.parse(*args)
            } catch (e: OptionException) {
                println(e.message)
                parser.printHelpOn(System.out)
                return
            }

            if (options.has(help)) {
                parser.printHelpOn(System.out)
                return
            }

            val dir = Paths.get(options.valueOf(dirArg))
            val params = NetworkParameters.fromPmtProtocolID(options.valueOf(netArg)) ?: throw IllegalArgumentException("invalid net parameter")

            setupLogging(dir, options.has(logToConsole))

            val crawler = Crawler(dir, params, options.valueOf(hostname))
            HttpSeed(if (options.has(httpAddress)) InetAddress.getByName(options.valueOf(httpAddress)) else null,
                options.valueOf(httpPort).toInt(), "", dir.resolve("privkey"), crawler, params.paymentProtocolId)
            if (options.has(dnsname)) {
                val s = options.valueOf(dnsname)
                DnsServer(Name(if (s.endsWith('.')) s else s + '.'), options.valueOf(dnsPort).toInt(), crawler).start()
            }
            crawler.start()

            Runtime.getRuntime().addShutdownHook(Thread { crawler.stop() })
        }

        private var loggerPin: Logger? = null
        private fun setupLogging(dir: Path, logToConsole: Boolean) {
            val logger = Logger.getLogger("")
            val handler = if (logToConsole) { ConsoleHandler() } else { FileHandler(dir.resolve("log.txt").toString(), true) }
            handler.formatter = BriefLogFormatter()
            logger.removeHandler(logger.handlers[0])
            logger.addHandler(handler)
            loggerPin = logger

            Logger.getLogger("org.bitcoinj").level = Level.SEVERE
        }
    }
}
