// NEW: what splice says on a wire that has gone quiet (2026-09-06).
//
// gpt-6-astra is served buffered end to end on this backend: probed live 2026-09-06, two identical
// requests returned every reasoning-summary event and the whole answer inside the last 300 ms of a
// 47 s and a 62 s turn, and its compactions run 5-12 minutes the same way (24-117 upstream events,
// first delta at the very end). There is nothing to forward while that runs, so the user watches a
// blank spinner for minutes with no way to tell a working turn from a hung one. The proxy knows the
// difference and says so. Every clause is read LIVE at the moment the line is written — elapsed off
// the turn's own t0, and whether the client has seen a delta off the perf marks — so the line can
// never claim liveness it is remembering from minutes ago.
//
// V4-451 (2026-10-01): one line per 30 s heartbeat stacked 8 lines on a 4-minute wait and 24 on a
// 12-minute compaction, repeating what Claude Code's own spinner already counts. Within one quiet
// stretch the line is now written at its 1st, 2nd, 4th and 8th heartbeat, doubling after that; the
// ping still goes out at every one. A stretch that begins after the model wrote opens its own block,
// so its first line is a whole sentence, never a "still" continuation of a block the reader no
// longer sees beside it.
//
// Failure-handling slice 2 (2026-10-26): the words moved to the turn's voice (SpliceLines.quiet), which
// names the head a person launched rather than the model behind it and says the time as a person says
// it. This class keeps the cadence.
package splice.head.turn

import splice.core.failure.QuietTurn
import splice.core.failure.SpliceLine
import splice.core.failure.TurnVoice

/** One per turn: decides which heartbeats of a quiet stretch speak, and asks the turn's [voice] for the
 *  line. Takes the facts rather than the turn, so what it says is testable without one. */
internal class TurnProgressLine(private val voice: TurnVoice) {

    private var beat = 0

    /** The line for this heartbeat, or null when the beat stays silent. [fresh] is whether the wire
     *  has no notice block open, so this line opens one and reads on its own: the turn's first quiet
     *  stretch, or a later one after the model wrote. A line inside an open block follows the one
     *  before it. [sawOutput] is whether the CLIENT has been handed a delta yet, which is the
     *  difference between a turn that has not started answering and one that stopped mid-answer. */
    fun next(elapsedMs: Long, sawOutput: Boolean, fresh: Boolean): SpliceLine? {
        beat = if (fresh) 1 else beat + 1
        // 1, 2, 4, 8, ... heartbeats into the stretch: a power of two.
        if (beat and (beat - 1) != 0) return null
        return voice.quiet(QuietTurn(elapsedMs, answering = sawOutput, again = !fresh))
    }
}
