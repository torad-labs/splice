// NEW: the body Claude Code sent on a session's newest captured turn, for Sessions' transcript fallback (gap 423,
// web-console.md:557: capture feeds the transcript view). A session whose transcript file is gone still has its
// conversation in the request the trace kept, and that request carries the whole of it up to that turn.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import splice.core.util.Cancellables
import splice.core.util.JsonScalars

/** One captured request: the turn it was sent on and the body exactly as the client sent it. */
public data class CapturedSent(val turnId: String, val body: String)

/** Reads [dir]'s trace synchronously, off the transcript route's own read; null wherever the trace holds nothing. */
public class TraceSessionSent(private val dir: TraceDirPort) {
    private val rows = TraceRows()

    /** The client body of [session]'s newest captured turn under [head], or null when the trace keeps none. */
    public fun newest(head: String, session: String): CapturedSent? {
        val traceDir = dir() ?: return null
        val turn = Cancellables.runCatchingBestEffort {
            rows.turns(traceDir, head, TraceAsk(1, session = session)).lastOrNull()
        }.getOrNull() ?: return null
        val body = turn.attempts.firstNotNullOfOrNull { JsonScalars.str(it["client"] as? JsonObject, "body") }
        return body?.let { CapturedSent(turn.id, it) }
    }
}
