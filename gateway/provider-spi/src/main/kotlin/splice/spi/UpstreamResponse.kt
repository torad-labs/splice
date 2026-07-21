// NEW: a thin wrapper over the Ktor streaming response so the gateway pipeline reads the body
// channel + headers without depending on ktor-client types directly.
package splice.spi

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream

public class UpstreamResponse(private val resp: HttpResponse) {
    public val status: Int get() = resp.status.value

    public fun header(name: String): String? = resp.headers[name]

    public suspend fun bodyChannel(): ByteReadChannel = resp.bodyAsChannel()
}

internal suspend fun HttpResponse.bodyTextLimited(maxBytes: Int): String {
    val channel = bodyAsChannel()
    val output = ByteArrayOutputStream(minOf(maxBytes, ERROR_READ_BUFFER_BYTES))
    val buffer = ByteArray(ERROR_READ_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = readAvailableOrEof(channel, buffer)
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

private suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Int {
    var read = channel.readAvailable(buffer, 0, buffer.size)
    while (read == 0) {
        if (!channel.awaitContent(1)) return -1
        read = channel.readAvailable(buffer, 0, buffer.size)
    }
    return read
}

private fun limitedText(output: ByteArrayOutputStream, truncated: Boolean): String =
    buildString {
        append(output.toString(Charsets.UTF_8))
        if (truncated) append("\n[… omitted …]")
    }

private class UpstreamBodyLimitException(limit: Int) :
    RuntimeException("upstream error body exceeds $limit bytes")

private const val ERROR_READ_BUFFER_BYTES = 8 * 1024
