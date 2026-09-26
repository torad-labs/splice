// NEW: a thin wrapper over the Ktor streaming response so the gateway pipeline reads the body
// channel + headers without depending on ktor-client types directly.
package splice.upstream.transport

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import splice.upstream.failure.SseSpuriousWakeupException
import java.io.ByteArrayOutputStream
import java.io.IOException

public class UpstreamResponse(
    private val resp: HttpResponse,
    /** How [bodyTextLimited] opens the body channel — the real response in production, a torn-peer
     *  double in the DR-21 production-path test. Default references the ctor's [resp]. */
    private val bodyChannelSource: BodyChannelSource = BodyChannelSource { resp.bodyAsChannel() },
) {
    public val status: Int get() = resp.status.value

    public fun header(name: String): String? = resp.headers[name]

    public suspend fun bodyChannel(): ByteReadChannel = resp.bodyAsChannel()

    /** The bounded error-body read, through the injectable [bodyChannelSource] so THIS method — not
     *  just [LimitedBodyReader] — is pinned against re-inlining an uncaught walk: a spurious-wakeup
     *  storm mid-error-body must degrade to the truncated diagnostic, never replace the classified
     *  upstream status the caller holds with an unclassified IOException (DR-21). */
    internal suspend fun bodyTextLimited(maxBytes: Int): String =
        LimitedBodyReader().read(bodyChannelSource.open(), maxBytes)
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
