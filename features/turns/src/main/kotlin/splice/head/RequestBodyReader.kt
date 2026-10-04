// PORT-OF: splice/gateway/head/HeadServer.kt (receiveBodyBounded, readAvailableOrEof,
// ReceivedBody, RequestBodyTooLarge, READ_BUFFER_BYTES) @ 1caedd6 — invariants unchanged: bounded
// body I/O under the read timeout, the declared-Content-Length pre-check, the running-total cap,
// and the EOF-vs-zero-read disambiguation a raw readAvailable needs. Split out (HD-24); both
// nested shapes WIDENED private nested -> internal, because RequestBodyTooLarge is caught in
// AdmissionGate.materializeOrRespond and ReceivedBody is read by TurnPreparation and CountTokens.
package splice.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.withTimeout
import splice.upstream.transport.ChannelReads
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CoderResult
import java.nio.charset.CodingErrorAction

private const val READ_BUFFER_BYTES = 16 * 1024

// why: UTF-8 can leave at most three incomplete bytes before the next fixed read.
private const val UTF8_BOUNDARY_BYTES = 3

internal fun interface RequestBodyRead {
    suspend operator fun invoke(channel: ByteReadChannel, buffer: ByteArray): Int
}

private val processRequestBodyRead = RequestBodyRead(ChannelReads::readAvailableOrEof)

internal data class ReceivedBody(val text: String, val bytes: Int)

internal class RequestBodyTooLarge(val limit: Int) : RuntimeException()

/** UTF-8 staging stays in fixed segments, including partial characters across reads.
 *  The parser's final String is allocated once. Closing drops every staging segment even when
 *  a completed channel-read continuation remains retained by the transport.
 */
private class RequestBodyText(bufferBytes: Int) : AutoCloseable {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val input = ByteBuffer.allocate(bufferBytes + UTF8_BOUNDARY_BYTES)
    private val output = CharBuffer.allocate(bufferBytes.coerceAtLeast(2))
    private val parts = mutableListOf<String>()

    fun append(bytes: ByteArray, count: Int) {
        input.put(bytes, 0, count)
        decode(end = false)
        input.compact()
    }

    fun text(): String {
        decode(end = true)
        var result: CoderResult
        do {
            result = decoder.flush(output)
            drain()
        } while (result.isOverflow)
        return java.lang.String.join("", parts)
    }

    private fun decode(end: Boolean) {
        input.flip()
        var result: CoderResult
        do {
            result = decoder.decode(input, output, end)
            drain()
        } while (result.isOverflow)
    }

    private fun drain() {
        if (output.position() == 0) return
        parts += String(output.array(), 0, output.position())
        output.clear()
    }

    override fun close() {
        parts.clear()
    }
}

/** Reads a request body into memory with a hard byte cap and the head's read timeout. */
internal class RequestBodyReader(
    /** The ONE thing this collaborator needs from the head (V4-105 item 3): the read timeout. Name
     *  the SEAM, not the 25-parameter bundle it happened to live in. */
    private val requestReadTimeoutMs: Long,
    private val read: RequestBodyRead = processRequestBodyRead,
) {
    suspend fun receiveBodyBounded(call: ApplicationCall, limit: Int): ReceivedBody {
        return withTimeout(requestReadTimeoutMs) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limit) throw RequestBodyTooLarge(limit)
            receiveBodyBounded(call.receiveChannel(), declared, limit)
        }
    }

    suspend fun receiveBodyBounded(channel: ByteReadChannel, declared: Long?, limit: Int): ReceivedBody {
        return withTimeout(requestReadTimeoutMs) {
            if (declared != null && declared > limit) throw RequestBodyTooLarge(limit)
            val bufferBytes = minOf(declared ?: limit.toLong(), READ_BUFFER_BYTES.toLong()).coerceAtLeast(1).toInt()
            RequestBodyText(bufferBytes).use { output ->
                val buffer = ByteArray(bufferBytes)
                var total = 0
                // `count`, not `read`: the local must not shadow the injected read delegate.
                var count = read(channel, buffer)
                while (count >= 0) {
                    total += count
                    if (total > limit) throw RequestBodyTooLarge(limit)
                    output.append(buffer, count)
                    count = read(channel, buffer)
                }
                ReceivedBody(output.text(), total)
            }
        }
    }
}
