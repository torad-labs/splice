// NEW: V4-216 (2026-09-25) — `splice restart` lets a compaction in flight finish before the stop.
//
// A compaction is the one turn a restart must not cut. It runs for minutes, and the daemon keeps its
// answer for a client that retries it (CompactionReplay, FileCompactionRecordings), but only once the
// compaction has finished. The stop's own drain is 45 s inside a unit that allows 90 in all, so the wait
// comes BEFORE the stop: the daemon keeps serving and ordinary turns never hold a restart. A slot is waited
// for until the daemon's own whole-turn cap would end it, and the whole wait is held to the same span.
// 2026-10-13: before its first read the wait HOLDS new compactions (Head.compactionHold): one arriving after
// that read waits at admission instead of starting a summary the stop's 45 s drain would cut, and the drain
// answers it with the stopping refusal its client retries against the next daemon. `--now` skips all of it.
//
// 2026-10-13: the span was Claude Code's 600 s abort. Claude Code 2.1.296 no longer aborts a compaction
// at a flat 600 s: its recovery timer is 1.5 ms per token of the conversation, at least 600 s and at most
// an hour, and the launch sets API_TIMEOUT_MS to the head's turn cap plus a minute. So the daemon's
// cap, upstreamTimeoutMs, is now what ends a compaction, and a restart that cut one at 600 s threw away
// up to five minutes of a summary the client was still waiting for.
//
// V4-220: it lives beside the in-flight read it is built on, so `splice upgrade`'s own restart takes it
// too (UpgradeDaemon) with the dependency running one way, restart -> upgrade.
package splice.lifecycle.upgrade

import splice.core.config.Knob
import splice.core.terminal.TerminalOutput
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// why: the daemon ends a compaction at its whole-turn cap (WatchdogBudget.forCompact keeps only that wall), so a slot
// older than it is about to end anyway, and the whole wait is held to the same span. This is the knob's default: a
// command with a longer upstreamTimeoutMs is waited on this long and then restarted.
private val CAP_MS = Knob.UPSTREAM_TIMEOUT_MS.count()

// why: the hold outlasts the wait by the stop that follows it (the 45 s drain inside a unit that allows 90), and lapses
// on its own after that, so a restart that never comes holds nothing for long.
private const val HOLD_PAST_WAIT_MS = 120_000L

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
        inflight.hold(capMs + HOLD_PAST_WAIT_MS)?.let { why ->
            output.line("splice: new compactions are not held ($why); one that starts now gets only the stop's drain")
        }
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
