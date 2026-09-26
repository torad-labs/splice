// NEW: V4-272 (2026-09-26) — a request's write is bounded by the head's firstByteTimeout and named when
// it stalls. Its own file rather than a tail of UpstreamTransport.kt: the client construction there
// installs it with one line and needs nothing else from it.
package splice.upstream.transport

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * V4-272: a request's write is bounded by [writeTimeoutMs], the head's firstByteTimeout, and named when
 * it stalls. Before this the bound was the whole-turn cap: the client-wide write timeout is
 * socketTimeoutMillis, and the idle watchdog is armed only after a 2xx, so a request the upstream
 * stopped taking (a wifi drop left 195,768 bytes in one send queue for 264 s, film home 2026-09-26)
 * waited out the cap in silence. Only an application interceptor may change a call's timeouts.
 *
 * OkHttp's write timeout is per write, so a request the upstream keeps taking is never cut, however
 * slow, and one it stops taking is cut that long after the last bytes it took. A timeout thrown inside
 * the body's own write can only be a stalled write, so it is rethrown as [RequestWriteStalled], and the
 * retry loop resends on a fresh connection. The read timeout is untouched, so a prefill that answers
 * after the whole request is written waits exactly as long as it did.
 */
internal class RequestWriteBound(private val writeTimeoutMs: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val bounded = request.newBuilder().method(request.method, StallNamedBody(body, writeTimeoutMs)).build()
        val timeoutMs = writeTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return chain.withWriteTimeout(timeoutMs, TimeUnit.MILLISECONDS).proceed(bounded)
    }
}

/** [body], whose write names a timeout for what it is. */
private class StallNamedBody(private val body: RequestBody, private val writeTimeoutMs: Long) : RequestBody() {
    override fun contentType(): MediaType? = body.contentType()

    override fun contentLength(): Long = body.contentLength()

    override fun isOneShot(): Boolean = body.isOneShot()

    override fun isDuplex(): Boolean = body.isDuplex()

    override fun writeTo(sink: BufferedSink) {
        try {
            body.writeTo(sink)
            // The last buffered bytes go out here, inside the write, so a stall on them is named too.
            sink.flush()
        } catch (timeout: SocketTimeoutException) {
            throw RequestWriteStalled(writeTimeoutMs, timeout)
        }
    }
}
