// NEW (2026-09-06): what splice says on a wire that has gone quiet.
//
// gpt-6-astra is served buffered end to end on this backend: probed live 2026-09-06, two identical
// requests returned every reasoning-summary event and the whole answer inside the last 300 ms of a
// 47 s and a 62 s turn, and its compactions run 5-12 minutes the same way (24-117 upstream events,
// first delta at the very end). There is nothing to forward while that runs, so the user watches a
// blank spinner for minutes with no way to tell a working turn from a hung one. The proxy knows the
// difference and says so. Every clause is read LIVE at the moment the line is written — elapsed off
// the turn's own t0, and whether the client has seen a delta off the perf marks — so the line can
// never claim liveness it is remembering from minutes ago.
package splice.gateway.head

private const val MS_PER_S = 1000L
private const val S_PER_MIN = 60L

/** One per turn: composes the status line, and remembers only whether it has spoken before.
 *  Takes the three facts rather than the turn, so what it says is testable without one. */
internal class TurnProgressLine {

    private var spoken = false

    /** The next line for this turn, with the separator that appends it to the block already there.
     *  The first says what is happening and names the row; the rest are a ticker. [sawOutput] is
     *  whether the CLIENT has been handed a delta yet, which is the difference between a turn that
     *  has not started answering and one that stopped mid-answer. */
    fun next(elapsedMs: Long, model: String, sawOutput: Boolean): String {
        val elapsed = elapsed(elapsedMs)
        val line = when {
            !spoken && sawOutput ->
                "[splice] holding this turn open. $elapsed into the turn, $model has paused mid-answer."
            !spoken ->
                "[splice] holding this turn open. $elapsed into the turn, no output from $model yet."
            sawOutput -> "[splice] $elapsed, still paused."
            else -> "[splice] $elapsed, still waiting."
        }
        val separator = if (spoken) "\n" else ""
        spoken = true
        return separator + line
    }

    private fun elapsed(ms: Long): String {
        val seconds = ms / MS_PER_S
        val minutes = seconds / S_PER_MIN
        return if (minutes == 0L) "${seconds}s" else "${minutes}m${seconds % S_PER_MIN}s"
    }
}
