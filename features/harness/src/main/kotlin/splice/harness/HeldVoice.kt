// NEW: what a held turn says (docs/specs/harness.md, decisions 7, 12 and 13). One capability's work speaks through
// this: the capability's own message as its work starts, then each progress line its worker sends, a line with no
// message saying the last one again. The turn it speaks on is the one that holds the work now: a call the model made
// beside the client's tools works before any turn holds it, and is said on the client's next request once that waits.
package splice.harness

import splice.head.port.TurnHolding

internal class HeldVoice(message: String, private val color: String) : WorkerProgress {
    private var turn: TurnHolding? = null
    private var last = message

    /** [turn] holds the work from now on, and says what it said last, its age starting now. */
    @Synchronized
    fun heldBy(turn: TurnHolding) {
        this.turn = turn
        turn.says(last, color)
    }

    @Synchronized
    override fun said(message: String) {
        last = message
        turn?.says(message, color)
    }

    @Synchronized
    override fun again() {
        turn?.says(last, color)
    }
}
