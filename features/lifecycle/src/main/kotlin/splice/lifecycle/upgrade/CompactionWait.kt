// NEW: V4-216 (2026-09-25) — `splice restart` lets a compaction in flight finish before the stop.
//
// A compaction is the one turn a restart must not cut. It runs for minutes, and the daemon keeps its
// answer for a client that retries it (CompactionReplay, FileCompactionRecordings), but only once the
// compaction has finished. The stop itself waits for no turn (its drain is 0 since Oct 29, 2026: it cuts every running
// turn at once, in a unit that allows 75 s in all), so the wait comes BEFORE the stop: the daemon keeps serving and
// ordinary turns never hold a restart. A slot is waited for until the daemon's own whole-turn cap would end it (30
// minutes by default), and the whole wait is held to the same span.
// 2026-10-13: before its first read the wait HOLDS new compactions (Head.compactionHold): one arriving after
// that read waits at admission instead of starting a summary the stop would cut, and the stop answers it with the
// stopping refusal its client retries against the next daemon. `--now` skips all of it.
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

// why: the hold outlasts the wait by the stop that follows it (at most the 75 s the unit allows), and lapses on its own
// after that, so a restart that never comes holds nothing for long.
private const val HOLD_PAST_WAIT_MS = 120_000L

/** Which compactions a restart waits for, and why (for the one-time notice when it waits). */
internal enum class CompactionWaitMode {
    /** Wait for compactions on every command because the daemon does not run under the systemd unit and stops whole. */
    ALL_NO_UNIT,

    /** Wait for compactions on every command because `--wait` / `?wait=1` asked for it (or an older launcher). */
    ALL_REQUESTED,

    /** Hand-over restart: wait ONLY for compactions on commands that stop whole (`hands_over == false`). */
    STOPS_WHOLE_ONLY,
}

internal class CompactionWait(
    private val output: TerminalOutput,
    private val inflight: UpgradeInflight,
    private val pollMs: Long = INFLIGHT_POLL_MS,
    private val capMs: Long = CAP_MS,
) {
    /** Returns once no compaction in scope younger than [capMs] is in flight, or [capMs] has passed. A read
     *  that cannot see the slots is said and not waited on: the restart is what fixes a daemon too
     *  sick to answer /api/heads. */
    fun await(mode: CompactionWaitMode = CompactionWaitMode.ALL_REQUESTED) {
        val targets = resolveHoldTargets(mode) ?: return
        inflight.hold(capMs + HOLD_PAST_WAIT_MS, targets.heads)?.let { why ->
            output.line("splice: new compactions are not held ($why); one that starts now gets only the stop's drain")
        }
        pollUntilClear(mode)
    }

    /** Returns null when there is nothing to wait for (or the read failed); otherwise the heads to hold. */
    private fun resolveHoldTargets(mode: CompactionWaitMode): HoldTargets? {
        if (mode != CompactionWaitMode.STOPS_WHOLE_ONLY) return HoldTargets(null)
        val initial = inflight()
        if (initial is InflightRead.Unknown) {
            sayUnknown(initial.reason)
            return null
        }
        val stopWhole = (initial as? InflightRead.Count)?.stopWholeHeads ?: emptySet()
        return if (stopWhole.isEmpty()) null else HoldTargets(stopWhole)
    }

    private fun pollUntilClear(mode: CompactionWaitMode) {
        val start = TimeSource.Monotonic.markNow()
        val cap = capMs.milliseconds
        var explained = false
        var done = false
        while (!done) {
            val read = inflight()
            val pending = if (read is InflightRead.Unknown) {
                sayUnknown(read.reason)
                emptyList()
            } else {
                waitingIn(read, mode)
            }
            done = when {
                pending.isEmpty() -> true
                start.elapsedNow() >= cap -> {
                    output.line(
                        "splice: ${describe(pending)} still running after ${cap.inWholeSeconds}s; restarting anyway",
                    )
                    true
                }
                else -> {
                    if (!explained) {
                        explain(mode, pending)?.let(output::line)
                        explained = true
                    }
                    output.line("splice: waiting for ${describe(pending)}")
                    Thread.sleep(pollMs)
                    false
                }
            }
        }
    }

    private fun sayUnknown(reason: String) {
        output.line("splice: could not see compactions in flight ($reason); restarting without waiting")
    }

    private data class HoldTargets(val heads: Set<String>?)

    /** The compactions a restart waits for right now: each one in scope younger than [capMs] (V4-220: the
     *  daemon's own restart reads this once to decide between draining now and waiting). */
    fun waiting(mode: CompactionWaitMode = CompactionWaitMode.ALL_REQUESTED): List<CompactionSlot> =
        waitingIn(inflight(), mode)

    private fun waitingIn(read: InflightRead, mode: CompactionWaitMode): List<CompactionSlot> {
        val count = read as? InflightRead.Count ?: return emptyList()
        val slots = count.compactions.filter { it.ageMs < capMs }
        return if (mode == CompactionWaitMode.STOPS_WHOLE_ONLY) {
            slots.filter { it.head in count.stopWholeHeads }
        } else {
            slots
        }
    }

    private fun explain(mode: CompactionWaitMode, pending: List<CompactionSlot>): String? = when (mode) {
        CompactionWaitMode.ALL_NO_UNIT ->
            "splice: waiting first for the compactions running on every command, which this restart would cut: " +
                "this daemon does not run under splice's systemd unit, so it stops whole. " +
                "--now restarts without waiting."
        CompactionWaitMode.STOPS_WHOLE_ONLY -> {
            val names = pending.map { it.head }.distinct().joinToString(", ")
            "splice: waiting first for the compactions running on $names, which this restart would cut: " +
                "a Muse command and a local runtime that pins conversations to slots stop whole at a restart, " +
                "while every other command hands over at once. --now restarts without waiting."
        }
        CompactionWaitMode.ALL_REQUESTED -> null
    }

    private fun describe(slots: List<CompactionSlot>): String {
        val noun = if (slots.size == 1) "compaction" else "compactions"
        return "${slots.size} $noun (${slots.joinToString("; ") { "${it.head}, ${age(it.ageMs)} in" }})"
    }

    private fun age(ms: Long): String = ms.milliseconds.toComponents { minutes, seconds, _ ->
        if (minutes == 0L) "${seconds}s" else "${minutes}m${seconds}s"
    }
}
