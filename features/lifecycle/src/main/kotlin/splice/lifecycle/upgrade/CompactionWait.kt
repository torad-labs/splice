// NEW: V4-216 (2026-09-25) — `splice restart` lets a compaction in flight finish before the stop.
//
// A compaction is the one turn a restart must not cut. It runs for minutes; its client (Claude Code)
// gives it 600 s and then retries the same bytes; and the daemon keeps its answer for that retry
// (CompactionReplay, FileCompactionRecordings), but only once the compaction has finished. The stop's
// own drain is 45 s inside a unit that allows 90 in all, so the wait comes BEFORE the stop: the daemon
// keeps serving, no head closes admission, and ordinary turns never hold a restart. A slot is waited
// for until it reaches the client's 600 s, and the whole wait is held to the same 600 s, so new
// compactions arriving meanwhile cannot hold a restart forever. `--now` skips it.
//
// V4-220: it lives beside the in-flight read it is built on, so `splice upgrade`'s own restart takes it
// too (UpgradeDaemon) with the dependency running one way, restart -> upgrade.
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// why: Claude Code aborts an auto-compaction at 600 s of wall clock and retries it; a slot older than
// that has no client waiting on its answer, and the whole wait is held to the same span.
private const val CAP_MS = 600_000L

internal class CompactionWait(
    private val output: TerminalOutput,
    private val inflight: UpgradeInflight,
    private val pollMs: Long = INFLIGHT_POLL_MS,
    private val capMs: Long = CAP_MS,
) {
    /** Returns once no compaction younger than [capMs] is in flight, or [capMs] has passed. A read
     *  that cannot see the slots is said and not waited on: the restart is what fixes a daemon too
     *  sick to answer /api/heads. */
    fun await() {
        val start = TimeSource.Monotonic.markNow()
        val cap = capMs.milliseconds
        while (true) {
            val read = inflight()
            if (read is InflightRead.Unknown) {
                output.line("splice: could not see compactions in flight (${read.reason}); restarting without waiting")
                return
            }
            val pending = waitingIn(read)
            if (pending.isEmpty()) return
            if (start.elapsedNow() >= cap) {
                val after = cap.inWholeSeconds
                output.line("splice: ${describe(pending)} still running after ${after}s; restarting anyway")
                return
            }
            output.line("splice: waiting for ${describe(pending)}")
            Thread.sleep(pollMs)
        }
    }

    /** The compactions a restart waits for right now: each one younger than [capMs] (V4-220: the
     *  daemon's own restart reads this once to decide between draining now and waiting). */
    fun waiting(): List<CompactionSlot> = waitingIn(inflight())

    private fun waitingIn(read: InflightRead): List<CompactionSlot> =
        (read as? InflightRead.Count)?.compactions.orEmpty().filter { it.ageMs < capMs }

    private fun describe(slots: List<CompactionSlot>): String {
        val noun = if (slots.size == 1) "compaction" else "compactions"
        return "${slots.size} $noun (${slots.joinToString("; ") { "${it.head}, ${age(it.ageMs)} in" }})"
    }

    private fun age(ms: Long): String = ms.milliseconds.toComponents { minutes, seconds, _ ->
        if (minutes == 0L) "${seconds}s" else "${minutes}m${seconds}s"
    }
}
