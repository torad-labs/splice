// PORT-OF: ControlServer.kt (receiveStatuslineBody, readAvailableOrEof) @ a77531a — invariants
// unchanged: the bounded read of the body Claude Code's statusline hook posts, split out of
// StatuslineRoute (LAYOUT-01) because reading a request body and rendering a statusline are two jobs.
package splice.usage.statusline

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import splice.core.wire.HttpStatus
import java.io.ByteArrayOutputStream

private const val MAX_STATUSLINE_BYTES = 64 * 1024
private const val STATUSLINE_READ_BUFFER_BYTES = 8 * 1024
private const val STATUSLINE_READ_TIMEOUT_MS = 2_000L

// A healthy channel never reports content it cannot deliver; a run of consecutive torn wakeups means
// the client is broken — end the read honestly rather than pin a core. Mirrors SseReader's bound
// (the constant there is file-private to :integrations-upstream, which :features-usage does not depend on).
private const val MAX_STATUSLINE_SPURIOUS_WAKEUPS = 1024

internal object StatuslineBodyRead {
    /**
     * The posted body, or null once the failure has ALREADY been answered on [call].
     *
     * One exit per failure shape lives here rather than as a return per arm in the route, so a new
     * body failure adds an arm instead of another early return. The two refusals splice decides
     * (too large, torn) ride [StatuslineBody]; only the timeout, which `withTimeout` raises, is caught.
     */
    suspend fun readOrRespond(call: ApplicationCall): String? {
        val body = try {
            receive(call)
        } catch (_: TimeoutCancellationException) {
            call.respondText("statusline body timed out", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
            return null
        }
        return when (body) {
            is StatuslineBody.Read -> body.text
            StatuslineBody.TooLarge -> {
                call.respondText(
                    "statusline body exceeds $MAX_STATUSLINE_BYTES bytes",
                    ContentType.Text.Plain,
                    HttpStatusCode(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large"),
                )
                null
            }
            StatuslineBody.Torn -> {
                // Same outward shape as the timeout above — the body never arrived — but a distinct
                // message so a torn client is tellable from a merely slow one.
                call.respondText("statusline body read torn", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
                null
            }
        }
    }

    private suspend fun receive(call: ApplicationCall): StatuslineBody =
        withTimeout(STATUSLINE_READ_TIMEOUT_MS) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_STATUSLINE_BYTES) {
                StatuslineBody.TooLarge
            } else {
                readWhole(call.receiveChannel())
            }
        }

    private suspend fun readWhole(channel: ByteReadChannel): StatuslineBody {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(STATUSLINE_READ_BUFFER_BYTES)
        var body: StatuslineBody? = null
        while (body == null) {
            body = when (val chunk = readAvailableOrEof(channel, buffer)) {
                is Chunk.Bytes -> append(output, buffer, chunk.count)
                Chunk.End -> StatuslineBody.Read(output.toString(Charsets.UTF_8))
                Chunk.Torn -> StatuslineBody.Torn
            }
        }
        return body
    }

    /** Appends one chunk, or answers [StatuslineBody.TooLarge] once the body would pass the cap. */
    private fun append(output: ByteArrayOutputStream, buffer: ByteArray, count: Int): StatuslineBody? {
        if (output.size() + count > MAX_STATUSLINE_BYTES) return StatuslineBody.TooLarge
        output.write(buffer, 0, count)
        return null
    }

    /**
     * Read the next chunk: its bytes, the end of the stream, or [Chunk.Torn].
     *
     * The same guarded shape as [splice.upstream.sse.SseReader]'s readChunk, for the same reason. On a
     * healthy channel `readAvailable` suspends inside `awaitContent` when the buffer is empty and
     * neither guard is reached. They exist for the TORN case — a half-closed / degenerate peer where
     * `readAvailable` returns 0 WITHOUT suspending and `awaitContent` keeps claiming content it
     * never delivers. In that state NEITHER call suspends, so the enclosing
     * [STATUSLINE_READ_TIMEOUT_MS] `withTimeout` cannot fire either: a timeout only lands at a
     * suspension point, and the degenerate loop has none. Claude Code's statusline hook posts on a
     * timer, so one misbehaving client would pin a core permanently.
     *
     * [currentCoroutineContext].ensureActive is the part that matters: it gives the loop a
     * cancellation point, which is also what lets the `withTimeout` above actually bound it. The cap
     * is the second half — it stops the loop burning a core for those two seconds and refuses to let
     * a channel that lies about content masquerade as the clean end of stream [Chunk.End] means.
     */
    private suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Chunk {
        var spuriousWakeups = 0
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read == 0) {
            currentCoroutineContext().ensureActive() // a cancelled/timed-out read exits here, never spins
            if (!channel.awaitContent(1)) return Chunk.End
            if (++spuriousWakeups >= MAX_STATUSLINE_SPURIOUS_WAKEUPS) return Chunk.Torn
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return if (read < 0) Chunk.End else Chunk.Bytes(read)
    }
}

/** What one read of the posted body produced. The refusals are VALUES the route answers each with its
 *  own status (kt-no-exception-as-outcome): a too-large or torn body is an ordinary per-request
 *  condition, not a broken invariant. */
private sealed class StatuslineBody {
    data class Read(val text: String) : StatuslineBody()

    data object TooLarge : StatuslineBody()

    /** A half-open client that kept CLAIMING content without ever delivering a byte. Deliberately not a
     *  [Read]: a torn body must never render a statusline as though the client had sent one. */
    data object Torn : StatuslineBody()
}

/** One step of [StatuslineBodyRead]'s body read. */
private sealed class Chunk {
    class Bytes(val count: Int) : Chunk()

    data object End : Chunk()

    /** The exhaustion end of the spurious-wakeup bound. */
    data object Torn : Chunk()
}
