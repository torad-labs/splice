// NEW: the WS wire frame and the atomic frame+epoch pair. Split from
// ResponsesWsSession.kt so the chaining state is not billed for the DTOs
// (concentration, 2026-08-19).
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonWire
import splice.core.util.LogSink

/** What to send on the wire for one WS round. [chained] is diagnostics + the WS-5 instrument: the
 *  count that must move when chaining engages, and must NOT when it bails. [fullSendReason] names
 *  a bail worth a log line (today: the server holds a tool call this turn never answers); null for
 *  the silent full sends (no chain yet, a new generation, a rewritten prefix). */
internal data class WsFrame(
    val json: String,
    val chained: Boolean,
    val fullSendReason: String? = null,
    /** Stable, content-free reason for a full send. Null only for an incremental frame. */
    val fullSendCode: String? = null,
)

/** The frame AND the epoch it was built under, captured under ONE lock acquisition (F7). Two
 *  acquisitions — frameFor then epochOf — left a window where a concurrent [ResponsesWsSession.cleared]
 *  bumped the epoch AFTER the frame was built on now-invalidated context: the frame chained onto
 *  dropped state while its captured (post-bump) epoch still matched at commit, resurrecting exactly
 *  what cleared existed to bar (a bypassed SSE turn's messages then classify as server-held and
 *  silently vanish). Capturing both atomically closes it. */
internal data class WsFrameAndEpoch(
    val frame: WsFrame,
    val epoch: Long,
    /** The request to commit when the server will hold more than the client's input shows: a
     *  compaction whose open calls were closed (WsCompactionClose). Null commits the request sent. */
    val committed: JsonObject? = null,
)

/** The largest request frame the peer takes, in UTF-8 bytes. Moved out of ResponsesWsRunner beside the frame it bounds
 *  on Nov 10 (concentration). A test in this module lowers [maxBytes] rather than building a 32 MiB frame. */
internal class WsFrameLimit(private val log: LogSink) {
    var maxBytes: Long = WS_FRAME_LIMIT_BYTES

    /** Whether [frame] is over [maxBytes] in UTF-8, said on the socket [label] names when it is. Every char is one to
     *  three bytes, so only a frame between those bounds is measured. */
    fun over(label: String, frame: String): Boolean {
        val length = frame.length.toLong()
        if (length * MAX_UTF8_BYTES_PER_CHAR <= maxBytes) return false
        val bytes = if (length > maxBytes) length else JsonWire.byteSize(frame)
        val over = bytes > maxBytes
        if (over) {
            log(
                "[ws] $label request frame is at least $bytes bytes, over the peer's limit of $maxBytes; " +
                    "not sent, HTTP serves the round\n",
            )
        }
        return over
    }
}

// The Responses websocket peer closes with 1007 on a request frame over 32 MiB (Nov 2026: a 31.68 MiB frame accepted, a
// 32.09 MiB one refused), so a larger frame is never sent; HTTP takes the same body.
private const val WS_FRAME_LIMIT_BYTES = 32L * 1024 * 1024

// why: UTF-16 to UTF-8 at most triples a char's size (a surrogate pair is two chars and four bytes).
private const val MAX_UTF8_BYTES_PER_CHAR = 3L
