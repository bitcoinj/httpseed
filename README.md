Bitcoin Cartographer
====================

Cartographer is a Bitcoin peer to peer network crawler and seed server.

*[Download Cartographer](https://github.com/mikehearn/httpseed/releases)*

Like all peer to peer networks, Bitcoin requires hard coded starting points (sometimes called directories) that can be used to
find some initial peers to connect to. Currently Bitcoin nodes and wallets use DNS lookups for this purpose. DNS is
simple but increasingly inadequate for our needs:

* It is unauthenticated so the procedure can be man-in-the-middle attacked. This doesn't matter much whilst the P2P
  network itself is unauthenticated, but we'd like to change that in future.
* It is unauditable because you cannot prove the server gave you a particular answer.
* It cannot be easily extended with more metadata, like what services a peer provides or what the keys are (in future).
* It's hard to put additional parameters to the query, so in practice you cannot customise the list you get back.
* Some platforms and languages don't expose low level DNS APIs and so cannot easily handle the multi-A record responses.
* Setting one up requires complicated DNS changes that are easy to mess up.
* When a host infected with malware also runs a Bitcoin node, automated anti-malware crawlers can misidentify DNS seeds
  as malicious command and control servers.

This application is a Bitcoin P2P network crawler and server with the following features:

* Can serve seed data using gzipped, digitally signed protocol buffers over HTTP. This fixes authentication,
  auditability, malware false positives and extensibility. The signature uses secp256k1.
* Can additionally serve data in JSON, XML, HTML and comma separated text formats for ease of use with other tools, like
  web browsers and shell scripts.
* Queries can be restricted using a service flags bit mask.
* And for good measure, can also serve DNS queries too with a simple built in DNS server.
* Crawl speed can be specified in terms of successful connects per second, rather than the crude number-of-threads
  approach used by other crawlers.
* Exports statistics and controls using JMX, so you can reconfigure it at runtime and view charts of things like
  connects/sec or CPU usage using any JMX console, like Mission Control.

There is support in bitcoinj git master (from 0.13 onwards) for using the protobuf based protocol.

The code is written in Kotlin (sort of like a smaller, simpler Scala), so it's concise and easy to hack. Despite the
large number of features it comes to only about 670 lines of code.

Usage
=====

You will need a Java 8 runtime. Then [grab the JAR](https://github.com/mikehearn/httpseed/releases/) and use it like this:

```
java -Xmx300m
     -jar httpseed.jar
     --dir=/path/to/a/working/directory
     --net={test,main}
     --http-port=8080
     --dns-hostname={main,test}.seed.example.com
     --dns-port=2053
     --hostname=example.com
     --log-to-console
     --crawls-per-sec=15
```

Most flags are self explanatory. A few that aren't:

* `-Xmx300m`: Here we are allocating a max of 300mb of RAM, which is plenty (real requirements are more like 100mb max).
  You can set it to less but watch out for CPU spinning (i.e. it's constantly garbage collecting) if you set it too low.
* `--hostname`: This should be the hostname of the box that is running the crawler. It will be resolved and used to
  substitute for a localhost IP if one is found, to avoid problems with machines that report their hostname wrong.
  It will also be put into the version message sent by the crawler so people know who to contact if there's a problem.
* `--dns-hostname` (optional): This is the name that the DNS server will respond to (via UDP only). It
  should be set to whatever hostname you have allocated for DNS if you have chosen to do so.
* `--crawls-per-sec`: Max connects per second to do. If this is set too high then you might not be able to crawl
  the full network because you'll max out your CPU or bandwidth and you will end up with initial connections timing
  out (then they won't be recrawled). 15 is a lower bound, it works OK for a weak VPS.

The other flags should be self explanatory. A signing key will be created and saved into the working directory the first
time you run the app. After that it'll be reused for signing. By running with --log-to-console you will see the public
key in hex and this can be distributed in applications (assuming you keep your private key safe of course!).

In many setups you will want to run the server as a non-root user. You can use iptables to redirect the DNS port. Then
just add an NS record for the right host name.


Using JMX
=========

It can be useful to monitor and control your crawler remotely. Cartographer exports various statistics and knobs
via a technology called JMX. JMX is not a perfect monitoring tool by any means, but it is extremely simple to implement
(look at jmx-console.kt to see how little code is required).

It's a bad idea to run with JMX active all the time, because it doesn't tunnel well via SSH and the setup below will
only be protected by a cleartext password. So don't use it unless you have a trustworthy connection.

To activate it, you need to do a few things:

1. Create a secure password, e.g. `password=$( head /dev/urandom | shasum )`
2. Add it to lib/management/jmxremote.password file in your JRE like this: `echo "controlRole $password" >>lib/management/jmxremote.password`
3. Make sure the permissions on that file are restricted to only you: `chmod 400 lib/management/jmxremote.password`
2. Add `-Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.port=7091` to the command line before `-jar` and run the crawler as before.

You can now use any JMX console to connect to the server and monitor/control it. For example the Mission Control
program that comes with the latest JDK is passable (I haven't yet found a JMX console I'd describe as excellent).
Run it locally on your desktop using the "jmc" command. Then add your hostname as a remote connection and open
"MBean Server". You should see a bunch of graphs and gauges showing memory, CPU and fragmentation. At the bottom
there is a row of tabs that expose more info about various things. Click "MBean Browser" to get a tree of objects
that are exporting stats and knobs in the server. Find the cartographer folder and select the "Console" item.

Now you can see various stats. The bold / yellow highlighted lines are variables that can be modified. You can
plot graphs of the ones that are changing by right clicking and selecting Visualise. From this screen you can
also submit manual recrawl requests. Include both IP address and port when doing so.

Possible future features
========================

Ideas for the future:

* Probe peers through Tor to check behaviours that lightweight clients find hard to check on their own.
* Find other ways to distribute the signed network maps (e.g. via the p2p network itself).