// PORT-OF: ControlServer.kt (statusline, receiveStatuslineBody, readAvailableOrEof) @ a77531a —
// invariants unchanged: the wire-plumbing half of a concern whose rendering half
// (StatuslineRenderer.kt) was already extracted; the exception exists only to unwind the buffer
// loop, so it travels with it.
package splice.control.api

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
import splice.control.StatuslineRenderer
import splice.core.config.ConfigService
import java.io.ByteArrayOutputStream

private const val MAX_STATUSLINE_BYTES = 64 * 1024
private const val STATUSLINE_READ_BUFFER_BYTES = 8 * 1024
private const val STATUSLINE_READ_TIMEOUT_MS = 2_000L
private const val CONTENT_TOO_LARGE_STATUS = 413

// A healthy channel never reports content it cannot deliver; a run of consecutive torn wakeups means
// the client is broken — end the read honestly rather than pin a core. Mirrors SseReader's bound
// (the constant there is file-private to :provider-spi, which :control does not depend on).
private const val MAX_STATUSLINE_SPURIOUS_WAKEUPS = 1024

internal class StatuslineRoute(
    private val resolver: HeadResolver,
    private val config: ConfigService,
) {
    private val renderers = HashMap<String, Pair<List<String>, StatuslineRenderer>>()

    suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.headByName(key).singleOrNull()
        if (managed == null) {
            call.respondText(key, ContentType.Text.Plain)
            return
        }
        val stdin = readBodyOrRespond(call) ?: return
        // getConfig(KEY), not the global view: everything else here is this head's (label, usage,
        // warn thresholds) and statuslineGitRoots is per-head overridable, so the unkeyed read
        // silently ignored [heads.<key>.overrides].statuslineGitRoots. Found by
        // kt-head-scoped-config-must-be-keyed on its first tree scan (2026-07-26). `key` is
        // non-empty here — the managed == null early return above guarantees it resolved.
        val roots = config.getConfig(key).statuslineGitRoots
        val renderer = synchronized(renderers) {
            val cached = renderers[managed.head.key]
            if (cached?.first == roots) {
                cached.second
            } else {
                StatuslineRenderer(managed.head.label, roots).also {
                    renderers[managed.head.key] = roots.toList() to it
                }
            }
        }
        val line = renderer.render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h)
        call.respondText(line, ContentType.Text.Plain)
    }

    /**
     * The posted body, or null once the failure has ALREADY been answered on [call].
     *
     * One exit per failure shape lives here rather than as a return per catch arm in [statusline],
     * so a new body failure adds an arm instead of another early return.
     */
    private suspend fun readBodyOrRespond(call: ApplicationCall): String? =
        try {
            receiveStatuslineBody(call)
        } catch (_: StatuslineBodyTooLarge) {
            call.respondText(
                "statusline body exceeds $MAX_STATUSLINE_BYTES bytes",
                ContentType.Text.Plain,
                HttpStatusCode(CONTENT_TOO_LARGE_STATUS, "Content Too Large"),
            )
            null
        } catch (_: TimeoutCancellationException) {
            call.respondText("statusline body timed out", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
            null
        } catch (_: StatuslineReadTorn) {
            // Same outward shape as the timeout above — the body never arrived — but a distinct
            // message so a torn client is tellable from a merely slow one.
            call.respondText("statusline body read torn", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
            null
        }

    private suspend fun receiveStatuslineBody(call: ApplicationCall): String =
        withTimeout(STATUSLINE_READ_TIMEOUT_MS) {
            val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declared != null && declared > MAX_STATUSLINE_BYTES) throw StatuslineBodyTooLarge()
            val channel = call.receiveChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STATUSLINE_READ_BUFFER_BYTES)
            var total = 0
            var read = readAvailableOrEof(channel, buffer)
            while (read >= 0) {
                total += read
                if (total > MAX_STATUSLINE_BYTES) throw StatuslineBodyTooLarge()
                output.write(buffer, 0, read)
                read = readAvailableOrEof(channel, buffer)
            }
            output.toString(Charsets.UTF_8)
        }

    /**
     * Read the next chunk; returns byte count (> 0) or -1 at end of stream.
     *
     * The same guarded shape as [splice.spi.SseReader]'s readChunk, for the same reason. On a
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
     * a channel that lies about content masquerade as the clean EOF that `-1` above means.
     */
    private suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Int {
        var spuriousWakeups = 0
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read == 0) {
            currentCoroutineContext().ensureActive() // a cancelled/timed-out read exits here, never spins
            if (!channel.awaitContent(1)) return -1
            if (++spuriousWakeups >= MAX_STATUSLINE_SPURIOUS_WAKEUPS) throw StatuslineReadTorn()
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return read
    }
}

private class StatuslineBodyTooLarge : RuntimeException()

/** The exhaustion end of [readAvailableOrEof]'s spurious-wakeup bound — a half-open client that kept
 *  CLAIMING content without ever delivering a byte. Local (not :provider-spi's
 *  SseSpuriousWakeupException) because :control depends on :core alone, and reaching for that type
 *  would mean a new module edge for one exception. Deliberately NOT reported as a clean read: a torn
 *  body must never render a statusline as though the client had sent one. */
private class StatuslineReadTorn : RuntimeException()
