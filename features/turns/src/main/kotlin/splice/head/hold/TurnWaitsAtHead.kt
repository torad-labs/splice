// NEW: failure-handling slice 4. Where a turn waits before it sends again (final-design.md section 8, "Lines from
// waits"). RetryEngine names each wait and the attempt loop takes it here: the wait runs on the head's own clock, and a
// wait of VISIBLE_WAIT_MS or more is told as it starts, in the turn's voice, as the line that opens a recovery episode.
// A shorter wait passes in silence. The terminal shows one line per episode, and before the response commits its
// channel stages the line (PendingSse) and shows it when the status does. A hold the engine names ends the post at
// once, with today's wire and the turn's interim line, until slice 5 holds a turn (RetrySteps).
package splice.head.hold

import splice.core.failure.FailureCause
import splice.core.failure.Position
import splice.core.failure.Recovery
import splice.core.failure.WaitLine
import splice.head.wire.TurnTerminal
import splice.upstream.TurnWaits
import splice.upstream.Waiter
import splice.upstream.retry.VISIBLE_WAIT_MS

/** One turn's waits: on [waiter], the head's clock, told through [terminal], the turn's own wire. */
internal class TurnWaitsAtHead(private val waiter: Waiter, private val terminal: TurnTerminal) : TurnWaits {
    override suspend fun wait(afterMs: Long, cause: FailureCause, at: Position) {
        if (afterMs >= VISIBLE_WAIT_MS) terminal.recovering(WaitLine(cause, Recovery.RetryIn(afterMs)))
        waiter.wait(afterMs)
    }
}
