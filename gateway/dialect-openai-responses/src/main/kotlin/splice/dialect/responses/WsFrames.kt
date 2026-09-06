// NEW: the WS wire frame and the atomic frame+epoch pair. Split from
// ResponsesWsSession.kt so the chaining state is not billed for the DTOs
// (concentration, 2026-08-19).
package splice.dialect.responses

/** What to send on the wire for one WS round. [chained] is diagnostics + the WS-5 instrument: the
 *  count that must move when chaining engages, and must NOT when it bails. [fullSendReason] names
 *  a bail worth a log line (today: the server holds a tool call this turn never answers); null for
 *  the silent full sends (no chain yet, a new generation, a rewritten prefix). */
internal data class WsFrame(val json: String, val chained: Boolean, val fullSendReason: String? = null)

/** The frame AND the epoch it was built under, captured under ONE lock acquisition (F7). Two
 *  acquisitions — frameFor then epochOf — left a window where a concurrent [ResponsesWsSession.cleared]
 *  bumped the epoch AFTER the frame was built on now-invalidated context: the frame chained onto
 *  dropped state while its captured (post-bump) epoch still matched at commit, resurrecting exactly
 *  what cleared existed to bar (a bypassed SSE turn's messages then classify as server-held and
 *  silently vanish). Capturing both atomically closes it. */
internal data class WsFrameAndEpoch(val frame: WsFrame, val epoch: Long)
