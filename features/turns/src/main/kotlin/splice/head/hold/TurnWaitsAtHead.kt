// NEW: failure-handling slice 4. Where a turn waits before it sends again (final-design.md section 8, "Lines from
// waits"). RetryEngine names each wait and the attempt loop takes it here: the wait runs on the head's own clock, and a
// wait of VISIBLE_WAIT_MS or more is told as it starts, in the turn's voice, as the line that opens a recovery episode.
// A shorter wait passes in silence. The terminal shows one line per episode, and the line commits the response when it
// is written (slice 5), so the client reads it while the wait it names runs.
//
// Slice 5: a hold the engine names is kept here too (TurnHold), in the same request, until its cause lifts. A turn
// with no hold at its head (a test's bare drive) ends as it did before holds.
package splice.head.hold

import io.ktor.server.application.ApplicationCall
import splice.core.client.CLIENT_WALL_HEADER
import splice.core.failure.FailureCause
import splice.core.failure.Position
import splice.core.failure.Recovery
import splice.core.failure.WaitLine
import splice.head.hold.resources.TurnHold
import splice.head.wire.TurnTerminal
import splice.upstream.TurnWaits
import splice.upstream.Waiter
import splice.upstream.retry.COMPACTION_TIMER_MAX_MS
import splice.upstream.retry.COMPACTION_TIMER_MICROS_PER_TOKEN
import splice.upstream.retry.COMPACTION_TIMER_MIN_MS
import splice.upstream.retry.HoldAsk
import splice.upstream.retry.HoldEnd
import splice.upstream.retry.OLD_CLIENT_WALL_MS
import splice.upstream.retry.VISIBLE_WAIT_MS

/** One turn's waits: on [waiter], the head's clock, told through [terminal], the turn's own wire, and its holds, kept
 *  by [hold] where the head holds turns. */
internal class TurnWaitsAtHead(
    private val waiter: Waiter,
    private val terminal: TurnTerminal,
    private val hold: TurnHold? = null,
) : TurnWaits {
    override suspend fun wait(afterMs: Long, cause: FailureCause, at: Position) {
        if (afterMs >= VISIBLE_WAIT_MS) terminal.recovering(WaitLine(cause, Recovery.RetryIn(afterMs)))
        waiter.wait(afterMs)
    }

    override suspend fun hold(ask: HoldAsk): HoldEnd = hold?.hold(ask) ?: HoldEnd.UNSUPPORTED

    override fun holdWallLeftMs(): Long = hold?.wallLeftMs() ?: Long.MAX_VALUE
}

/** How long a request's session waits for it: the client timeout its launch named in [CLIENT_WALL_HEADER], or the
 *  timeout every session had before slice 5 where it names none; and a compaction's own timer, where that is sooner. */
internal object ClientWallHeader {
    /** The client timeout [call] names, in milliseconds. */
    fun ms(call: ApplicationCall): Long =
        call.request.headers[CLIENT_WALL_HEADER]?.trim()?.toLongOrNull()?.takeIf { it > 0L } ?: OLD_CLIENT_WALL_MS

    /** The timer Claude Code aborts a compaction of about [bodyBytes] on: 1.5 ms a token, from 600 s to an hour, at
     *  about four bytes a token. */
    fun compactionTimerMs(bodyBytes: Long): Long {
        val tokens = bodyBytes / BYTES_PER_TOKEN
        return (tokens * COMPACTION_TIMER_MICROS_PER_TOKEN / MICROS_PER_MS)
            .coerceIn(COMPACTION_TIMER_MIN_MS, COMPACTION_TIMER_MAX_MS)
    }
}

/** why: a token of English prose and code is about four bytes, the estimate the compaction timer is read from. */
private const val BYTES_PER_TOKEN = 4L

/** why: the compaction timer is measured in microseconds per token. */
private const val MICROS_PER_MS = 1_000L
