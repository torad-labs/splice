// NEW: a thin wrapper over the Ktor streaming response so the gateway pipeline reads the body
// channel + headers without depending on ktor-client types directly.
package splice.spi

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream

public class UpstreamResponse(private val resp: HttpResponse) {
    public val status: Int get() = resp.status.value

    public fun header(name: String): String? = resp.headers[name]

    public suspend fun bodyChannel(): ByteReadChannel = resp.bodyAsChannel()

    /** The bounded error-body read. Was an `HttpResponse` extension; the walk lives in
     *  [LimitedBodyReader] so the torn-peer contract is testable without a real response. */
    internal suspend fun bodyTextLimited(maxBytes: Int): String =
        LimitedBodyReader().read(resp.bodyAsChannel(), maxBytes)
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
                channel.cancel(UpstreamBodyLimitException(maxBytes))
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

private class UpstreamBodyLimitException(limit: Int) :
    RuntimeException("upstream error body exceeds $limit bytes")

private const val ERROR_READ_BUFFER_BYTES = 8 * 1024
