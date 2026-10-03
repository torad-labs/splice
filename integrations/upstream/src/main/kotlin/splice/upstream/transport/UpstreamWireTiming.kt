// NEW: V4-456 — timestamps the actual OkHttp request flush and first positive response read.
package splice.upstream.transport

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import splice.core.perf.UpstreamAttemptTiming

/** Network-side observation, before event decoding or synchronous downstream delivery. */
internal class UpstreamWireTiming : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val timing = request.tag<UpstreamAttemptTiming>() ?: return chain.proceed(request)
        val body = request.body ?: return chain.proceed(request)
        val marked = request.newBuilder().method(request.method, WrittenBody(body, timing)).build()
        val response = chain.proceed(marked)
        val responseBody = response.body
        return response.newBuilder().body(FirstByteBody(responseBody, timing)).build()
    }

    private class WrittenBody(
        private val body: RequestBody,
        private val timing: UpstreamAttemptTiming,
    ) : RequestBody() {
        override fun contentType(): MediaType? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun isOneShot(): Boolean = body.isOneShot()
        override fun isDuplex(): Boolean = body.isDuplex()

        override fun writeTo(sink: BufferedSink) {
            body.writeTo(sink)
            // writeTo alone can leave the request's tail in okio's buffer. Flush it to the socket first.
            sink.flush()
            timing.written()
        }
    }

    private class FirstByteBody(body: ResponseBody, timing: UpstreamAttemptTiming) : ResponseBody() {
        private val type = body.contentType()
        private val length = body.contentLength()
        private val observed = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val count = super.read(sink, byteCount)
                if (count > 0) timing.firstByte()
                return count
            }
        }.buffer()

        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length
        override fun source(): BufferedSource = observed
    }
}
