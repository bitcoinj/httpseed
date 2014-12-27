package net.plan99.bitcoin.cartographer

import java.nio.file.*
import java.util.logging.*
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.core.NetworkParameters
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import joptsimple.OptionParser
import kotlin.platform.platformStatic

public class BitcoinHTTPSeed {
    class object {
        platformStatic
        fun main(args: Array<String>) {
            val parser = OptionParser()
            val netArg = parser.accepts("net").withRequiredArg().defaultsTo("test")
            val dirArg = parser.accepts("dir").withRequiredArg()
            val httpPort = parser.accepts("http-port").withRequiredArg().defaultsTo("8080")
            val dnsPort = parser.accepts("dns-port").withRequiredArg().defaultsTo("2053")
            val hostname = parser.accepts("hostname").withRequiredArg()
            val dnsname = parser.accepts("dnsname").withRequiredArg()
            val logToConsole = parser.accepts("log-to-console")
            val crawlsPerSec = parser.accepts("crawls-per-sec").withRequiredArg().ofType(javaClass<Int>()).defaultsTo(20)

            val options = parser.parse(*args)

            if (!options.has(dirArg)) {
                println("You must specify a working directory with --dir=/path/to/directory")
                return
            }
            if (!options.has(hostname)) {
                println("You must specify the public --hostname of this machine.")
                return
            }
            val dir = Paths.get(options.valueOf(dirArg))

            val params = NetworkParameters.fromPmtProtocolID(options.valueOf(netArg))

            setupLogging(dir, options.has(logToConsole))

            val console = setupJMX()
            console.allowedConnectsPerSec = options.valueOf(crawlsPerSec)
            val crawler = Crawler(console, dir, params, options.valueOf(hostname))
            HTTPServer(options.valueOf(httpPort).toInt(), "", dir.resolve("privkey"), crawler, params.getPaymentProtocolId())
            if (options.has(dnsname)) {
                val s = options.valueOf(dnsname)
                DnsServer(if (s.endsWith('.')) s else s + '.', options.valueOf(dnsPort).toInt(), crawler).start()
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
            handler.setFormatter(BriefLogFormatter())
            logger.addHandler(handler)
            if (logToConsole) {
                logger.getHandlers()[0].setFormatter(BriefLogFormatter())
            } else {
                logger.removeHandler(logger.getHandlers()[0])
            }
            loggerPin = logger

            Logger.getLogger("org.bitcoinj").setLevel(Level.SEVERE)
        }
    }
}
