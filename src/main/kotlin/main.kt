package net.plan99.bitcoin.cartographer

import java.net.InetAddress
import java.nio.file.*
import java.util.logging.*
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.core.NetworkParameters
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import joptsimple.OptionParser
import org.xbill.DNS.Name
import joptsimple.OptionException

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
                println(e.getMessage())
                parser.printHelpOn(System.out)
                return
            }

            if (options.has(help)) {
                parser.printHelpOn(System.out)
                return
            }

            val dir = Paths.get(options.valueOf(dirArg))
            val params = NetworkParameters.fromPmtProtocolID(options.valueOf(netArg))

            setupLogging(dir, options.has(logToConsole))

            val console = setupJMX()
            console.allowedConnectsPerSec = options.valueOf(crawlsPerSec)
            val crawler = Crawler(console, dir, params, options.valueOf(hostname))
            HttpSeed(if (options.has(httpAddress)) InetAddress.getByName(options.valueOf(httpAddress)) else null,
                options.valueOf(httpPort).toInt(), "", dir.resolve("privkey"), crawler, params.paymentProtocolId)
            if (options.has(dnsname)) {
                val s = options.valueOf(dnsname)
                DnsServer(Name(if (s.endsWith('.')) s else s + '.'), options.valueOf(dnsPort).toInt(), crawler).start()
            }
            crawler.start()
        }

        private fun setupJMX(): Console {
            val mbs = ManagementFactory.getPlatformMBeanServer();
            val name = ObjectName("net.plan99.bitcoin.cartographer:type=Console")
            val console = Console()
            mbs.registerMBean(console, name)
            return console
        }

        private var loggerPin: java.util.logging.Logger? = null
        private fun setupLogging(dir: Path, logToConsole: Boolean) {
            val logger = java.util.logging.Logger.getLogger("")
            val handler = FileHandler(dir.resolve("log.txt").toString(), true)
            handler.formatter = BriefLogFormatter()
            logger.addHandler(handler)
            if (logToConsole) {
                logger.handlers[0].formatter = BriefLogFormatter()
            } else {
                logger.removeHandler(logger.handlers[0])
            }
            loggerPin = logger

            Logger.getLogger("org.bitcoinj").level = Level.SEVERE
        }
    }
}
