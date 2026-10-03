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
import kotlinx.io.Buffer
import kotlinx.io.asOutputStream
import kotlinx.io.readByteArray
import splice.upstream.transport.ChannelReads
import java.io.ByteArrayOutputStream
import java.io.OutputStream

private const val READ_BUFFER_BYTES = 16 * 1024

internal fun interface RequestBodyRead {
    suspend operator fun invoke(channel: ByteReadChannel, buffer: ByteArray): Int
}

private val processRequestBodyRead = RequestBodyRead(ChannelReads::readAvailableOrEof)

internal data class ReceivedBody(val text: String, val bytes: Int)

internal class RequestBodyTooLarge(val limit: Int) : RuntimeException()

/** Known lengths retain exact preallocation; unknown lengths grow by pooled segments, never whole-buffer copies. */
private interface RequestBodyBuffer : AutoCloseable {
    val sink: OutputStream
    fun decode(): String

    override fun close() {
        sink.close()
    }

    class Declared(capacity: Int) : RequestBodyBuffer {
        private val bytes = ByteArrayOutputStream(capacity)
        override val sink: OutputStream = bytes
        override fun decode(): String = bytes.toString(Charsets.UTF_8)
    }

    class Chunked : RequestBodyBuffer {
        private val bytes = Buffer()
        override val sink: OutputStream = bytes.asOutputStream()
        override fun decode(): String = bytes.readByteArray().toString(Charsets.UTF_8)
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
            val capacity = minOf(declared?.toInt() ?: READ_BUFFER_BYTES, limit).coerceAtLeast(0)
            val output: RequestBodyBuffer =
                if (declared == null) RequestBodyBuffer.Chunked() else RequestBodyBuffer.Declared(capacity)
            output.use {
                val buffer = ByteArray(READ_BUFFER_BYTES)
                var total = 0
                // `count`, not `read`: the local must not shadow the injected read delegate.
                var count = read(channel, buffer)
                while (count >= 0) {
                    total += count
                    if (total > limit) throw RequestBodyTooLarge(limit)
                    output.sink.write(buffer, 0, count)
                    count = read(channel, buffer)
                }
                ReceivedBody(output.decode(), total)
            }
        }
    }
}
