Bitcoin HTTP Seed
=================

HTTPSeed is a Bitcoin peer to peer network crawler and seed server.

*[Download HTTPSeed](https://github.com/bitcoinj/httpseed/releases)*

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
* Can additionally serve data in comma separated text format for ease of use with other tools, like
  web browsers and shell scripts.
* Queries can be restricted using a service flags bit mask.
* And for good measure, can also serve DNS queries too with a simple built in DNS server.
* Crawl speed can be specified in terms of successful connects per second, rather than the crude number-of-threads
  approach used by other crawlers.

There is support in bitcoinj git master (from 0.13 onwards) for using the protobuf based protocol.

The code is written in Kotlin (sort of like a smaller, simpler Scala), so it's concise and easy to hack. Despite the
large number of features it comes to only about 670 lines of code.

Building
========

You will need a Java 8 (or later) SDK and Gradle. Run:

```shell script
./gradlew clean shadowJar
```

Usage
=====

You will need a Java 8 (or later) runtime. After building, use it like this:

```shell script
java -Xmx300m
     -jar build/libs/httpseed-all.jar
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

Docker
======

To build the docker image (including HTTPSeed itself), run:

```shell script
docker build -t httpseed .
```

To run HTTPSeed inside docker, run:

```shell script
docker run -it --rm -v /tmp/data:/data -p 8080:8080 httpseed --hostname=example.com
```

Make sure to map the volume to a persistent location, as it contains your signing key.
