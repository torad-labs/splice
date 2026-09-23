// PORT-OF: ControlServer.kt (statusline, receiveStatuslineBody, readAvailableOrEof) @ a77531a —
// invariants unchanged: the wire-plumbing half of a concern whose rendering half
// (StatuslineRenderer.kt) was already extracted; the exception exists only to unwind the buffer
// loop, so it travels with it.
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.config.ConfigService
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.version.ClientVersionTracker
import splice.core.wire.HttpStatus
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.perf.HeadPerfSkipSource
import splice.usage.perf.HeadSessionPerfSource
import java.io.ByteArrayOutputStream

private const val MAX_STATUSLINE_BYTES = 64 * 1024
private const val STATUSLINE_READ_BUFFER_BYTES = 8 * 1024
private const val STATUSLINE_READ_TIMEOUT_MS = 2_000L

// A healthy channel never reports content it cannot deliver; a run of consecutive torn wakeups means
// the client is broken — end the read honestly rather than pin a core. Mirrors SseReader's bound
// (the constant there is file-private to :integrations-upstream, which :features-usage does not depend on).
private const val MAX_STATUSLINE_SPURIOUS_WAKEUPS = 1024

public class StatuslineRoute(
    private val heads: UsageHeadLookup,
    private val config: ConfigService,
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
) {
    private val renderers = RendererCache()
    private val json = Json { ignoreUnknownKeys = true }

    public suspend fun statusline(call: ApplicationCall) {
        val key = call.parameters["head"].orEmpty()
        val managed = heads.byName(key).singleOrNull()
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
        val renderer = renderers.get(managed.key, managed.label, roots) {
            StatuslineRenderer(
                managed.label,
                roots,
                catalog = managed.catalog,
                clientWindows = managed.clientWindows,
                accountPool = managed.accountPool,
                sessionCost = sessionCostOf(managed),
                // V4-45: the same checked-cast bridge sessionCostOf uses below, and captured the
                // same way — the SOURCE, never a count, so the cached renderer reads it live.
                perfSkips = managed.perf as? HeadPerfSkipSource,
            )
        }
        val sessionId = sessionId(stdin)
        val line = renderer.render(stdin, managed.usage, managed.warnPct, managed.warnTokens5h, sessionId)
        val warning = clientVersions.statuslineWarning(sessionId)
        call.respondText(warning?.let { "$line · $it" } ?: line, ContentType.Text.Plain)
    }

    /** V4-37: the per-session cost, when this head can price one at all.
     *
     *  `perf` is typed [splice.usage.perf.HeadPerfSource] and the session-aware reader is its SIBLING
     *  interface, so this bridge is a checked cast. A head whose perf source cannot answer per
     *  session — every test double, and any future sink that keeps no session column — renders the
     *  client's own number, exactly as today. The head-level rate override is null here because the
     *  TOML field that populates it is stage two (HeadConfig, V4-36's file). */
    private fun sessionCostOf(managed: UsageHead): SessionCostSource? =
        (managed.perf as? HeadSessionPerfSource)?.let { perf -> SessionCost(perf, managed.catalog) }

    // A statusline payload splice did not author and cannot answer to: a missing session id is the
    // absence of an OPTIONAL field, not a failure, and the render path has no sink — it degrades to
    // the no-session view.
    // ast-grep-ignore: kt-no-silent-result-collapse -- a missing optional session id is absence, not a failure
    private fun sessionId(stdin: String): String? = Cancellables.runCatchingCancellable {
        JsonScalars.str(json.parseToJsonElement(stdin).jsonObject, "session_id")
    }.getOrNull()

    /**
     * The posted body, or null once the failure has ALREADY been answered on [call].
     *
     * One exit per failure shape lives here rather than as a return per arm in [statusline], so a
     * new body failure adds an arm instead of another early return. The two refusals splice decides
     * (too large, torn) ride [StatuslineBody]; only the timeout, which `withTimeout` raises, is caught.
     */
    private suspend fun readBodyOrRespond(call: ApplicationCall): String? {
        val body = try {
            receiveStatuslineBody(call)
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

    private suspend fun receiveStatuslineBody(call: ApplicationCall): StatuslineBody =
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

/** One step of [StatuslineRoute]'s body read. */
private sealed class Chunk {
    class Bytes(val count: Int) : Chunk()

    data object End : Chunk()

    /** The exhaustion end of the spurious-wakeup bound. */
    data object Torn : Chunk()
}
