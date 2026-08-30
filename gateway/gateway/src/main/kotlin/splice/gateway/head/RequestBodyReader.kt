// PORT-OF: splice/gateway/head/HeadServer.kt (receiveBodyBounded, readAvailableOrEof,
// ReceivedBody, RequestBodyTooLarge, READ_BUFFER_BYTES) @ 1caedd6 — invariants unchanged: bounded
// body I/O under the read timeout, the declared-Content-Length pre-check, the running-total cap,
// and the EOF-vs-zero-read disambiguation a raw readAvailable needs. Split out (HD-24); both
// nested shapes WIDENED private nested -> internal, because RequestBodyTooLarge is caught in
// AdmissionGate.materializeOrRespond and ReceivedBody is read by TurnPreparation and CountTokens.
package splice.gateway.head

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.withTimeout
import splice.spi.ChannelReads
import java.io.ByteArrayOutputStream

private const val READ_BUFFER_BYTES = 16 * 1024

internal fun interface RequestBodyRead {
    suspend operator fun invoke(channel: ByteReadChannel, buffer: ByteArray): Int
}

private val processRequestBodyRead = RequestBodyRead(ChannelReads::readAvailableOrEof)

internal data class ReceivedBody(val text: String, val bytes: Int)

internal class RequestBodyTooLarge(val limit: Int) : RuntimeException()

/** Reads a request body into memory with a hard byte cap and the head's read timeout. */
internal class RequestBodyReader(
    private val deps: HeadDeps,
    private val read: RequestBodyRead = processRequestBodyRead,
) {
    suspend fun receiveBodyBounded(call: ApplicationCall, limit: Int): ReceivedBody {
        return withTimeout(deps.requestReadTimeoutMs) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > limit) throw RequestBodyTooLarge(limit)
            val channel = call.receiveChannel()
            val capacity = minOf(declared?.toInt() ?: READ_BUFFER_BYTES, limit).coerceAtLeast(0)
            val output = ByteArrayOutputStream(capacity)
            val buffer = ByteArray(READ_BUFFER_BYTES)
            var total = 0
            var read = read(channel, buffer)
            while (read >= 0) {
                total += read
                if (total > limit) throw RequestBodyTooLarge(limit)
                output.write(buffer, 0, read)
                read = read(channel, buffer)
            }
            ReceivedBody(output.toString(Charsets.UTF_8), total)
        }
    }
}
