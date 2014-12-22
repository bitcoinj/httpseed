Bitcoin Cartographer
====================

Cartographer is a Bitcoin peer to peer network crawler and seed server.

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
* Can additionally serve data in JSON, XML and HTML formats for ease of use with other tools, like web browsers.
* Queries can be restricted using a service flags bit mask.
* And for good measure, can also serve DNS queries too with a simple built in DNS server.
* Crawl speed can be specified in terms of successful connects per second, rather than the crude number-of-threads
  approach used by other crawlers.
* Exports statistics and controls using JMX, so you can reconfigure it at runtime and view charts of things like
  connects/sec or CPU usage using any JMX console, like Mission Control.

There is support in bitcoinj git master (from 0.13-SNAPSHOT onwards) for using the protobuf based protocol.

The code is written in Kotlin (sort of like a smaller, simpler Scala), so it's concise and easy to hack. Despite the
large number of features it comes to only about 570 lines of code.

Usage
=====

Currently you need to compile it yourself. Grab yourself a JDK 8 and Maven then do "mvn package" (usual story).

Once you have the JAR, use it like this:

java -jar httpseed.jar --dir=/path/to/a/working/directory --net={test,main} --http-port=8080 --dns-port=2053 --hostname=seed.example.com --log-to-console

Flags should be self explanatory. A signing key will be created and saved into the working directory the first time you
run the app. After that it'll be reused for signing. By running with --log-to-console you will see the public key in
hex and this can be distributed in applications (assuming you keep your private key safe of course!).

Possible future features
========================

Ideas for the future:

* Probe peers through Tor to check behaviours that lightweight clients find hard to check on their own.
* Find other ways to distribute the signed network maps (e.g. via the p2p network itself).