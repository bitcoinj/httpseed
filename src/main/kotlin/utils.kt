package net.plan99.bitcoin.cartographer

import com.google.common.net.HostAndPort
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.protobuf.ByteString
import org.bitcoinj.utils.Threading
import java.net.InetSocketAddress

fun parseIPAndPort(ipAndPort: String): InetSocketAddress {
    val hostAndPort = HostAndPort.fromString(ipAndPort.trim())
    val sockaddr = InetSocketAddress(hostAndPort.hostText, hostAndPort.port)
    return sockaddr
}

infix fun <T> ListenableFuture<T>.later(action: (T) -> Unit) {
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onSuccess(result: T?) {
            action(result!!)
        }

        override fun onFailure(t: Throwable) {
            throw t
        }
    }, Threading.USER_THREAD)
}

infix fun <T: Any> ListenableFuture<T>.later(action: (T?, Throwable?) -> Unit) {
    Futures.addCallback(this, object : FutureCallback<T> {
        override fun onSuccess(result: T?) {
            action(result, null)
        }

        override fun onFailure(t: Throwable) {
            action(null, t)
        }
    }, Threading.USER_THREAD)
}

fun ByteArray.toByteString(): ByteString = ByteString.copyFrom(this)

public inline fun <T> Int.gatherTimes(body : () -> T): List<T> {
    val result = ArrayList<T>(this)
    for (i in 0..this)
        result.add(body())
    return result
}
