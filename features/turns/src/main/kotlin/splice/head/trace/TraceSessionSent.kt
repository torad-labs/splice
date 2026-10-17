// NEW: the body Claude Code sent on a session's newest captured turn, for Sessions' transcript fallback (gap 423,
// web-console.md:557: capture feeds the transcript view). A session whose transcript file is gone still has its
// conversation in the request the trace kept, and that request carries the whole of it up to that turn.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import splice.core.memory.HeapReadView
import splice.core.memory.HeapReservations
import splice.core.storage.DayFiles
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.upstream.memory.JvmHeap
import java.io.Reader

/** One captured request: the turn it was sent on and the body exactly as the client sent it. */
public data class CapturedSent(val turnId: String, val body: String)

/** Close every opened reader before this source. Each pass verifies the exact stored chunks it consumes. */
public class CapturedSentReader internal constructor(
    private val source: TraceCaptureRead,
    private val share: HeapReadView,
) : AutoCloseable {
    public val turnId: String get() = checkNotNull(source.turnId)
    public val heap: HeapReservations get() = share

    public fun open(): Reader = checkNotNull(source.open()) { "captured trace body is no longer available" }

    override fun close() {
        Cancellables.withCleanup({ share.close() }) { source.close() }
    }
}

/** Reads [dir]'s trace synchronously, off the transcript route's own read; null wherever the trace holds nothing. */
public class TraceSessionSent(private val dir: TraceDirPort) {
    private val rows = TraceRows()

    /** A repeatable, read-scoped literal source for paging, without materializing any complete packed body. */
    public fun open(head: String, session: String): CapturedSentReader? {
        val traceDir = dir() ?: return null
        val share = JvmHeap.budget.readShare()
        var sourceReady = false
        val source = Cancellables.withCleanup({ if (!sourceReady) share.close() }) {
            TraceCaptureRead(TraceAsk(1, session = session), share).also { sourceReady = true }
        }
        var readerReady = false
        val reader: CapturedSentReader = Cancellables.withCleanup({
            if (!readerReady) Cancellables.withCleanup({ share.close() }) { source.close() }
        }) {
            CapturedSentReader(source, share).also { readerReady = true }
        }
        var retained = false
        return Cancellables.withCleanup({ if (!retained) reader.close() }) {
            DayFiles(traceDir, head).newestFirst(source)
            if (!source.available) {
                null
            } else {
                reader.also { retained = true }
            }
        }
    }

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
