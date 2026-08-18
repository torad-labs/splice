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
import kotlinx.coroutines.withTimeout
import splice.control.StatuslineRenderer
import splice.core.config.ConfigService
import java.io.ByteArrayOutputStream

private const val MAX_STATUSLINE_BYTES = 64 * 1024
private const val STATUSLINE_READ_BUFFER_BYTES = 8 * 1024
private const val STATUSLINE_READ_TIMEOUT_MS = 2_000L
private const val CONTENT_TOO_LARGE_STATUS = 413

internal class StatuslineRoute(
    private val resolver: HeadResolver,
    private val config: ConfigService,
) {
    suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = resolver.headByName(key).singleOrNull()
        if (managed == null) {
            call.respondText(managed?.head?.label ?: key, ContentType.Text.Plain)
            return
        }
        val stdin = try {
            receiveStatuslineBody(call)
        } catch (_: StatuslineBodyTooLarge) {
            call.respondText(
                "statusline body exceeds $MAX_STATUSLINE_BYTES bytes",
                ContentType.Text.Plain,
                HttpStatusCode(CONTENT_TOO_LARGE_STATUS, "Content Too Large"),
            )
            return
        } catch (_: TimeoutCancellationException) {
            call.respondText("statusline body timed out", ContentType.Text.Plain, HttpStatusCode.RequestTimeout)
            return
        }
        // getConfig(KEY), not the global view: everything else here is this head's (label, usage,
        // warn thresholds) and statuslineGitRoots is per-head overridable, so the unkeyed read
        // silently ignored [heads.<key>.overrides].statuslineGitRoots. Found by
        // kt-head-scoped-config-must-be-keyed on its first tree scan (2026-07-26). `key` is
        // non-empty here — the managed == null early return above guarantees it resolved.
        val line = StatuslineRenderer(managed.head.label, config.getConfig(key).statuslineGitRoots)
            .render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h)
        call.respondText(line, ContentType.Text.Plain)
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

    private suspend fun readAvailableOrEof(channel: ByteReadChannel, buffer: ByteArray): Int {
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read == 0) {
            if (!channel.awaitContent(1)) return -1
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return read
    }
}

private class StatuslineBodyTooLarge : RuntimeException()
