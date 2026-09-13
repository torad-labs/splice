// NEW (v0.4.0, FEATURES.md §5): the running daemon's side of an upgrade — wait until every head's
// inflight on /api/heads is zero (or --now), restart the user unit when one supervises the daemon
// (hostshield's splice.service) and the plain restart verb otherwise, then run the NEW jar's doctor.
package splice.app.cli

import java.nio.file.Path

private const val POLL_MS = 2_000L
private const val MAX_WAIT_MS = 30L * 60 * 1000
private const val USER_UNIT = "splice.service"
private const val NANOS_PER_MS = 1_000_000L

/** Restarts the daemon the plain way (`splice restart`: stop, then cold start from this shell). */
internal fun interface DaemonRestart {
    operator fun invoke(): Boolean
}

internal class UpgradeDaemon(
    private val process: UpgradeProcess,
    private val inflight: UpgradeInflight,
    private val restartVerb: DaemonRestart = DaemonRestart { RestartCommand().restart() },
    private val pollMs: Long = POLL_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
) {
    /** False when turns were still in flight after [maxWaitMs]; the candidate stays staged. */
    fun waitIdle(now: Boolean): Boolean {
        if (now) return true
        val start = System.nanoTime()
        var busy = inflight() ?: 0
        while (busy > 0 && (System.nanoTime() - start) / NANOS_PER_MS < maxWaitMs) {
            println("  ${"waiting".padEnd(UPGRADE_PAD)} $busy turn(s) in flight; restarting when they finish")
            Thread.sleep(pollMs)
            busy = inflight() ?: 0
        }
        return busy == 0
    }

    /** The user unit when one supervises THIS install (its ExecStart names [liveJar] — hostshield's
     *  splice.service does), else the restart verb's own stop + cold start. An install under another
     *  share dir (a second copy, a test home) never restarts someone else's unit. */
    fun restart(liveJar: Path): Boolean {
        val unit = process(listOf("systemctl", "--user", "show", "-p", "ExecStart", "--value", USER_UNIT), false)
        val supervised = unit.code == 0 && unit.stdout.contains(liveJar.toString())
        return if (supervised) {
            process(listOf("systemctl", "--user", "restart", USER_UNIT), true).code == 0
        } else {
            restartVerb()
        }
    }

    fun doctor(java: String, jar: Path): Boolean =
        process(listOf(java, "-jar", jar.toString(), "doctor"), true).code == 0
}
