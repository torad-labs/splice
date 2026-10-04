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
package splice.head.turn

private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60L

/** One per turn: composes the status line, and remembers whether it has introduced itself and how
 *  many heartbeats the current quiet stretch has had. Takes the facts rather than the turn, so what
 *  it says is testable without one. */
internal class TurnProgressLine {

    private var introduced = false
    private var beat = 0

    /** The line for this heartbeat, or null when the beat stays silent. [fresh] is whether the wire
     *  has no notice block open, so this line opens one: the turn's first quiet stretch, or a later
     *  one after the model wrote. The turn's first line says what is happening and names the row; a
     *  later block's first line restates the wait in one sentence; lines inside a block are a ticker
     *  that leads with the separator. [sawOutput] is whether the CLIENT has been handed a delta yet,
     *  which is the difference between a turn that has not started answering and one that stopped
     *  mid-answer. */
    fun next(elapsedMs: Long, model: String, sawOutput: Boolean, fresh: Boolean): String? {
        beat = if (fresh) 1 else beat + 1
        // 1, 2, 4, 8, ... heartbeats into the stretch: a power of two.
        if (beat and (beat - 1) != 0) return null
        val elapsed = elapsed(elapsedMs)
        val wait = if (sawOutput) "$model has paused mid-answer" else "no answer from $model yet"
        val line = when {
            !fresh && sawOutput -> "\n[splice] $elapsed, still paused."
            !fresh -> "\n[splice] $elapsed, still waiting."
            introduced -> "[splice] $elapsed into the turn, $wait."
            else -> "[splice] holding this turn open. $elapsed into the turn, $wait."
        }
        introduced = true
        return line
    }

    private fun elapsed(ms: Long): String {
        val seconds = ms / MS_PER_S
        val minutes = seconds / S_PER_MIN
        return if (minutes == 0L) "${seconds}s" else "${minutes}m${seconds % S_PER_MIN}s"
    }
}
