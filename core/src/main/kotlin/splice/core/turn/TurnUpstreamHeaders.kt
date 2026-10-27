// NEW: one turn's first upstream routing header, shared by its retries and continuation rounds, and (fix 4 of the
// Oct 27 Responses fixes) by every request of the Claude Code turn it belongs to.
package splice.core.turn

/** A first-write-wins header holder. Providers choose the header; transports only echo its snapshot.
 *
 *  One holder serves one request Claude Code sent: its retries and continuation rounds. A Claude Code turn spans the
 *  person's prompt and each tool-result request after it, and codex-rs keeps one turn state for that whole span
 *  (core/src/client.rs: ModelClientSession's turn_state), so a later request of the same turn [continues] an earlier
 *  one's holder: both then read and fill one value, the first the backend returned in that turn. */
public class TurnUpstreamHeaders {
    @Volatile
    private var turn = FirstHeader()
    private var httpOmissionNoticed = false

    /** An absent header does not initialize the holder, and later observations cannot replace it. */
    public fun capture(name: String, value: String?) {
        turn.capture(name, value)
    }

    /** An immutable request snapshot, empty until the upstream supplies a value in this turn. */
    public fun snapshot(): Map<String, String> = turn.snapshot()

    /** This request continues the Claude Code turn [earlier] belongs to: it echoes what that turn captured, and what
     *  it captures first is the turn's. Called before this request is sent, so nothing was captured here yet. */
    public fun continues(earlier: TurnUpstreamHeaders) {
        turn = earlier.turn
    }

    /** Claims one value-free HTTP omission notice for this request, not one per retry or round. */
    @Synchronized
    public fun claimHttpOmissionNotice(): Boolean {
        if (httpOmissionNoticed) return false
        httpOmissionNoticed = true
        return true
    }

    /** The first header a turn captured: one per Claude Code turn, shared by the holders of its requests. */
    private class FirstHeader {
        private var captured: Pair<String, String>? = null

        @Synchronized
        fun capture(name: String, value: String?) {
            if (captured == null && value != null) captured = name to value
        }

        @Synchronized
        fun snapshot(): Map<String, String> = captured?.let { mapOf(it) }.orEmpty()
    }
}
