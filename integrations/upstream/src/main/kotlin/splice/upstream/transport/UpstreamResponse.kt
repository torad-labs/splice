// NEW: a thin wrapper over the Ktor streaming response so the gateway pipeline reads the body
// channel + headers without depending on ktor-client types directly.
package splice.upstream.transport

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.auth.CredentialKey
import splice.core.usage.QuotaHeaderRead
import splice.core.util.Cancellables
import splice.upstream.failure.SseSpuriousWakeupException
import java.io.ByteArrayOutputStream
import java.io.IOException

public class UpstreamResponse(
    private val resp: HttpResponse,
    /** How [bodyTextLimited] opens the body channel — the real response in production, a torn-peer
     *  double in the DR-21 production-path test. Default references the ctor's [resp]. */
    private val bodyChannelSource: BodyChannelSource = BodyChannelSource { resp.bodyAsChannel() },
) {
    private var opened: ByteReadChannel? = null

    /** This attempt's POST start on its TurnPerf clock; absent when the request was not instrumented.
     *  Written by the transport before this response reaches its consumer, never inherited from a retry. */
    public var postedAtMs: Long? = null
        internal set

    public val status: Int get() = resp.status.value

    public fun header(name: String): String? = resp.headers[name]

    /** Observe this response with its actual request identity; no auth provider is called again. */
    public fun observeQuota(into: CredentialQuotaReceiver) {
        val headers = resp.call.request.headers.entries().associate { (name, values) -> name to values.last() }
        into.observe(CredentialKey.fromHeaders(headers), QuotaHeaderRead(::header))
    }

    /** The body, opened once: ktor hands a reply's channel out a single time, so the reader and [drainTail] must
     *  share the one this returns. */
    public suspend fun bodyChannel(): ByteReadChannel = opened ?: bodyChannelSource.open().also { opened = it }

    /** The bounded error-body read, through the injectable [bodyChannelSource] so THIS method — not
     *  just [LimitedBodyReader] — is pinned against re-inlining an uncaught walk: a spurious-wakeup
     *  storm mid-error-body must degrade to the truncated diagnostic, never replace the classified
     *  upstream status the caller holds with an unclassified IOException (DR-21). */
    internal suspend fun bodyTextLimited(maxBytes: Int): String =
        LimitedBodyReader().read(bodyChannel(), maxBytes)

    /**
     * Reads what a stream left behind its last word, so the connection goes back to the pool. A reader that stops
     * at the protocol's own end (the chat finish, the Responses completed event) leaves the body's last bytes
     * unread, and ktor 3.6.1 cancels the call of a reply whose body was not read to its end
     * (HttpStatement.cleanup), which closes the socket unless the transport had already taken those bytes off the
     * wire. 3.6.0 only cancelled the channel, and OkHttp's body close drained for up to 100 ms
     * (ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS); this is that drain, bounded the same way in time and in bytes.
     *
     * Only for a stream the upstream ended. A stream the turn ends on purpose (a client gone, a reaped round, a
     * runaway frame) is left alone: what is still coming is not a tail, and its connection is closed. Never throws
     * for a torn or timed-out body, whose connection is gone or discarded either way.
     *
     * @return true when the body's end was read within [maxBytes] and [withinMs], so the connection is reusable.
     */
    public suspend fun drainTail(maxBytes: Int = TAIL_DRAIN_MAX_BYTES, withinMs: Long = TAIL_DRAIN_MS): Boolean {
        val channel = bodyChannel()
        val ended = withTimeoutOrNull(withinMs) {
            Cancellables.runCatchingCleanup { readToEnd(channel, maxBytes) }.getOrDefault(false)
        }
        return ended == true
    }

    private suspend fun readToEnd(channel: ByteReadChannel, maxBytes: Int): Boolean {
        val buffer = ByteArray(TAIL_READ_BYTES)
        var total = 0
        while (true) {
            val read = ChannelReads.readAvailableOrEof(channel, buffer)
            if (read == -1) return true
            total += read
            if (total > maxBytes) return false
        }
    }
}

// why: what a stream's last word may leave behind (a [DONE] line, an end-of-stream marker) fits in a few hundred
// bytes; more is not a tail but a reply still being sent, which the connection is not worth reading for.
private const val TAIL_DRAIN_MAX_BYTES = 8 * 1024

// why: OkHttp's own bound for draining an unread body (ExchangeCodec.DISCARD_STREAM_TIMEOUT_MILLIS), which is what
// ktor 3.6.0 left in force; a server holding its stream open past its last word costs a turn this long at most.
private const val TAIL_DRAIN_MS = 100L

// why: one read's worth of a tail that is a few hundred bytes at most; small enough that the byte bound above is
// checked several times before it is reached, so a reply still being sent is given up on early.
private const val TAIL_READ_BYTES = 1024

/** Receives response headers with the request's private credential join key, never a wire-visible field. */
public fun interface CredentialQuotaReceiver {
    public fun observe(key: String?, headers: QuotaHeaderRead)
}

/** How [UpstreamResponse.bodyTextLimited] acquires the body channel — the real streaming response in
 *  production, a torn-peer double in the DR-21 production-path test. A fun interface (not a raw
 *  suspend lambda) per kt-no-lambda-seam; named for the ROLE. */
public fun interface BodyChannelSource {
    public suspend fun open(): ByteReadChannel
}

/** The bounded error-body walk, split from [UpstreamResponse] for the torn-peer test (DR-21):
 *  this is the one spurious-wakeup consolidation leg whose behavior nothing pinned. */
internal class LimitedBodyReader {
    suspend fun read(channel: ByteReadChannel, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, ERROR_READ_BUFFER_BYTES))
        val buffer = ByteArray(ERROR_READ_BUFFER_BYTES)
        var total = 0
        while (true) {
            // A spurious-wakeup storm mid-error-body degrades to the truncated diagnostic (DR-21):
            // the upstream STATUS the caller already holds is the turn's signal; letting the
            // wakeup exception fly replaced that classified failure with an unclassified one.
            val read = try {
                ChannelReads.readAvailableOrEof(channel, buffer)
            } catch (_: SseSpuriousWakeupException) {
                return limitedText(output, truncated = true)
            }
            if (read == -1) return limitedText(output, truncated = false)
            val remaining = maxBytes - total
            if (read > remaining) {
                if (remaining > 0) output.write(buffer, 0, remaining)
                // The cancel cause is DIAGNOSTIC ONLY — nothing catches it by name and the caller
                // already holds the classified upstream status — so it is a plain IOException:
                // a named RuntimeException subclass no reader branches on is a type with no job
                // (kt-no-exception-as-outcome, V4-114).
                channel.cancel(IOException("upstream error body exceeds $maxBytes bytes"))
                return limitedText(output, truncated = true)
            }
            output.write(buffer, 0, read)
            total += read
        }
    }

    private fun limitedText(output: ByteArrayOutputStream, truncated: Boolean): String =
        buildString {
            append(output.toString(Charsets.UTF_8))
            if (truncated) append("\n[… omitted …]")
        }
}

private const val ERROR_READ_BUFFER_BYTES = 8 * 1024
