// A request's body, read under a cap and a read timeout, as a head's own admission reads one: one byte past the cap is
// all that is ever held of a body too large, and a body declared past the cap is refused before any of it is read. The
// LAN share's relay reads every body through this, and so does a server's telemetry relay (server mode, F8).
package splice.share

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import splice.core.wire.HttpStatus

public class CappedBodies(private val voice: RefusalVoice) {
    /** [call]'s body, or null once it is refused in [voice]: 413 for a body declared or read past [cap], 408 for one
     *  still arriving after [readTimeoutMs]. */
    public suspend fun read(call: ApplicationCall, cap: Int, readTimeoutMs: Long): ByteArray? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val read = if (declared != null && declared > cap) {
            null
        } else {
            try {
                // One byte past the cap is enough to know the body is too large; no more is ever held.
                withTimeout(readTimeoutMs) { call.receiveChannel().readBuffer(cap + 1L).readByteArray() }
            } catch (_: TimeoutCancellationException) {
                val why = "the request body was still arriving after ${readTimeoutMs}ms"
                voice.refuse(call, HttpStatus.REQUEST_TIMEOUT, why)
                return null
            }
        }
        if (read != null && read.size <= cap) return read
        voice.refuse(call, HttpStatus.CONTENT_TOO_LARGE, "request body exceeds $cap bytes")
        return null
    }
}
