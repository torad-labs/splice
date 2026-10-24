// NEW: an upstream request body reaches the socket one okio segment at a time instead of all at once.
//
// Ktor hands OkHttp a byte body that writes itself with one sink.write, and okio's buffered sink copies a
// whole array into its buffer before it emits any of it: a 2 MB body took 2 MB of new 8 KiB segments,
// far more than okio's segment pool keeps. On the live daemon on Oct 20 those segments, under
// RequestSendState's writeTo, were 1,016 of 6,750 allocation samples. Written in segment-sized pieces, each
// full segment goes down to the socket before the next piece is copied, and the socket's write hands it back
// to the pool, so a body allocates a few segments whatever its size. okio's sink is a sealed interface, so
// the pieces are cut by a body of ours, which replaces Ktor's here.
package splice.upstream.transport

import io.ktor.util.AttributeKey
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Per-client handoff, like [UpstreamTimingBridge]: Ktor exposes no request-tag transfer, so an internal
 *  header carries a nonce as far as this application interceptor, which puts the attempt's bytes back as a
 *  segmented body and removes the header before any other interceptor or socket write. The caller releases
 *  the registration on every exit. A request whose nonce or length does not match keeps Ktor's body. */
internal class UpstreamBodyBridge : Interceptor {
    private val pending = ConcurrentHashMap<String, ByteArray>()
    private val next = AtomicLong()

    fun register(bytes: ByteArray): String {
        val token = next.incrementAndGet().toString()
        pending[token] = bytes
        return token
    }

    fun release(token: String) {
        pending.remove(token)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = request.header(UPSTREAM_BODY_HEADER) ?: return chain.proceed(request)
        val builder = request.newBuilder().removeHeader(UPSTREAM_BODY_HEADER)
        segmented(request.body, pending.remove(token))?.let { builder.method(request.method, it) }
        return chain.proceed(builder.build())
    }

    private fun segmented(body: RequestBody?, bytes: ByteArray?): RequestBody? {
        if (body == null || bytes == null) return null
        return if (body.contentLength() == bytes.size.toLong()) SegmentedBytes(bytes, body.contentType()) else null
    }

    /** [bytes], written to the sink in pieces no larger than one okio segment. */
    private class SegmentedBytes(private val bytes: ByteArray, private val type: MediaType?) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = bytes.size.toLong()

        override fun writeTo(sink: BufferedSink) {
            var position = 0
            while (position < bytes.size) {
                val piece = minOf(SEGMENT_BYTES, bytes.size - position)
                sink.write(bytes, position, piece)
                position += piece
            }
        }
    }
}

internal val upstreamBodyBridgeKey = AttributeKey<UpstreamBodyBridge>("splice-upstream-body")
internal const val UPSTREAM_BODY_HEADER = "x-splice-upstream-body"

// okio's segment size: a piece this size fills at most the tail of one segment and the head of the next.
private const val SEGMENT_BYTES = 8_192
