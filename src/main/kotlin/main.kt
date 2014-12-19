package net.plan99.bitcoin.crawler

import java.nio.file.Path
import java.util.logging.FileHandler
import org.bitcoinj.utils.BriefLogFormatter
import java.util.logging.Level
import java.nio.file.Paths
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import java.util.logging.Logger
import joptsimple.OptionParser
import org.bitcoinj.core.NetworkParameters
import kotlin.platform.platformStatic
import joptsimple.OptionSpec

public class BitcoinHTTPSeed {
    class object {
        platformStatic
        fun main(args: Array<String>) {
            val parser = OptionParser()
            val netArg = parser.accepts("net").withRequiredArg().defaultsTo("test")
            val dirArg = parser.accepts("dir").withRequiredArg()
            val httpPort = parser.accepts("http-port").withRequiredArg().defaultsTo("8080")
            val dnsPort = parser.accepts("dns-port").withRequiredArg().defaultsTo("2053")
            val dnsName = parser.accepts("hostname").withRequiredArg()
            val logToConsole = parser.accepts("log-to-console")
            val crawlsPerSec = parser.accepts("crawls-per-sec").withRequiredArg().ofType(javaClass<Int>()).defaultsTo(20)

            val options = parser.parse(*args)

            if (!options.has(dirArg)) {
                println("You must specify a working directory with --dir=/path/to/directory")
                return
            }
            val dir = Paths.get(options.valueOf(dirArg))

            val params = NetworkParameters.fromPmtProtocolID(options.valueOf(netArg))

            setupLogging(dir, options.has(logToConsole))

            val console = setupJMX()
            console.allowedSuccessfulConnectsPerSec = options.valueOf(crawlsPerSec)
            val crawler = Crawler(console, dir, params)
            HTTPServer(options.valueOf(httpPort).toInt(), "", dir.resolve("privkey"), crawler, params.getPaymentProtocolId())
            if (options.has(dnsName))
                DnsServer(options.valueOf(dnsName), options.valueOf(dnsPort).toInt(), crawler).start()
            crawler.start()
        }

        private fun setupJMX(): Console {
            val mbs = ManagementFactory.getPlatformMBeanServer();
            val name = ObjectName("net.plan99.bitcoin.crawler:type=Console")
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
