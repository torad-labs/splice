// NEW: v0.4.0 FEATURES.md §5 — the running daemon's side of an upgrade — wait until every head's
// inflight on /api/heads is zero (or --now), restart the user unit when one supervises the daemon
// (any splice.service the host installed) and the plain restart verb otherwise, then CONFIRM: the daemon that
// answers /health must report the version that was just activated. Neither path's exit code is that
// proof — the restart verb polled for the OLD CLI's own version and called a good restart a failure,
// and a unit whose file names the jar but whose daemon was started by hand restarted a JVM that lost
// the daemon lock and exited 0 while the squatter kept serving the old jar (review 2026-09-14).
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput
import java.nio.file.Path

private const val MAX_WAIT_MS = 30L * 60 * 1000
private const val NANOS_PER_MS = 1_000_000L
private const val CONFIRM_POLLS = 60
private const val CONFIRM_POLL_MS = 250L

/** Restarts the daemon the plain way (`splice restart`: stop, then cold start from this shell),
 *  waiting for a daemon that reports [expectedVersion]. The restart verb is app's; it arrives here. */
public fun interface VersionedRestart {
    public operator fun invoke(expectedVersion: String): Boolean
}

/** What /health reports as the daemon's version right now, or null when nothing answers. */
internal fun interface DaemonVersionRead {
    operator fun invoke(): String?
}

/** The outcome of a restart, decided by what answers /health afterwards. */
internal sealed class DaemonRestarted {
    /** The daemon serves the activated version. */
    object Serving : DaemonRestarted()

    /** A daemon answers, but with another version: the restart did not reach the one serving. */
    data class StillOld(val version: String) : DaemonRestarted()

    /** Nothing answers /health after the restart. */
    object NotAnswering : DaemonRestarted()
}

internal class UpgradeDaemon(
    private val output: TerminalOutput,
    private val process: UpgradeProcess,
    private val inflight: UpgradeInflight,
    private val restartVerb: VersionedRestart,
    private val healthVersion: DaemonVersionRead,
    private val pollMs: Long = INFLIGHT_POLL_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val confirmPollMs: Long = CONFIRM_POLL_MS,
    /** V4-176: the unit that supervises this install, by name, from the knob layer. splice does not
     *  own it and cannot assume one is there — an absent or inactive unit is the "started by hand"
     *  arm below, which the restart verb handles. Hardcoding the name made a box whose packager
     *  called the unit something else look permanently unsupervised. */
    userUnit: String,
) {
    private val unit = UpgradeUnit(userUnit, process)

    /** True only when every head reports zero turns, or no daemon exists at all. A read that cannot
     *  see the turns (key, timeout, shape) is waited out like a busy one and then refused: an unknown
     *  count is never treated as zero. False leaves the candidate staged. */
    fun waitIdle(now: Boolean): Boolean {
        if (now) return true
        val start = System.nanoTime()
        var read = inflight()
        while (!idle(read) && (System.nanoTime() - start) / NANOS_PER_MS < maxWaitMs) {
            output.line("  ${"waiting".padEnd(UPGRADE_PAD)} ${describe(read)}")
            Thread.sleep(pollMs)
            read = inflight()
        }
        if (read is InflightRead.Unknown) {
            output.line("  ${"waiting".padEnd(UPGRADE_PAD)} ${describe(read)}; not activating")
        }
        return idle(read)
    }

    private fun idle(read: InflightRead): Boolean =
        read is InflightRead.NoDaemon || (read as? InflightRead.Count)?.turns == 0

    private fun describe(read: InflightRead): String = when (read) {
        is InflightRead.Count -> "${read.turns} turn(s) in flight; restarting when they finish"
        is InflightRead.Unknown -> "in-flight count unknown (${read.reason})"
        InflightRead.NoDaemon -> "no daemon running"
    }

    /** The user unit when one supervises THIS install (UpgradeUnit), else the restart verb,
     *  whose stop is the only one that reaches a daemon started by hand. Whatever ran, the verdict is
     *  /health's: it must report [version].
     *
     *  V4-220: the release activated AFTER [waitIdle], and a compaction that started meanwhile is the one
     *  turn a restart must not cut, so either path first takes the wait the console's restart and `splice
     *  restart` take (CompactionWait). [now] skips it, as `--now` skips [waitIdle]. */
    fun restart(liveJar: Path, version: String, now: Boolean): DaemonRestarted {
        if (!now) CompactionWait(output, inflight, pollMs).await()
        if (unit.supervises(liveJar)) {
            unit.restart()
        } else {
            restartVerb(version)
        }
        return confirm(version)
    }

    private fun confirm(version: String): DaemonRestarted {
        var seen: String? = null
        repeat(CONFIRM_POLLS) {
            seen = healthVersion()
            if (seen == version) return DaemonRestarted.Serving
            Thread.sleep(confirmPollMs)
        }
        return seen?.let { DaemonRestarted.StillOld(it) } ?: DaemonRestarted.NotAnswering
    }

    fun doctor(java: String, jar: Path): Boolean =
        process(listOf(java, "-jar", jar.toString(), "doctor"), true).code == 0
}
