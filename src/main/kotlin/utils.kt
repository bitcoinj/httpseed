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
    val sockaddr = InetSocketAddress(hostAndPort.host, hostAndPort.port)
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
