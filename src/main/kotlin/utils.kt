package net.plan99.bitcoin.cartographer

import com.google.common.util.concurrent.*
import org.bitcoinj.utils.Threading
import com.google.protobuf.ByteString
import java.util.ArrayList
import java.util.Collections
import java.net.InetSocketAddress
import com.google.common.net.HostAndPort
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import sun.net.www.protocol.http.HttpURLConnection


fun parseIPAndPort(ipAndPort: String): InetSocketAddress {
    val hostAndPort = HostAndPort.fromString(ipAndPort.trim())
    val sockaddr = InetSocketAddress(hostAndPort.getHostText(), hostAndPort.getPort())
    return sockaddr
}

fun <T> ListenableFuture<T>.later(action: (T) -> Unit) {
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onSuccess(result: T) {
            action(result)
        }

        override fun onFailure(t: Throwable) {
            throw t
        }
    }, Threading.USER_THREAD)
}

fun <T: Any> ListenableFuture<T>.later(action: (T?, Throwable?) -> Unit) {
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onSuccess(result: T) {
            action(result, null)
        }

        override fun onFailure(t: Throwable) {
            action(null, t)
        }
    }, Threading.USER_THREAD)
}

fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)

public inline fun Int.gatherTimes<T>(body : () -> T): List<T> {
    val result = ArrayList<T>(this)
    for (i in 0..this)
        result.add(body())
    return result
}

public fun <T> List<T>.shuffle(): ArrayList<T> {
    val copy = ArrayList(this)
    Collections.shuffle(copy)
    return copy
}