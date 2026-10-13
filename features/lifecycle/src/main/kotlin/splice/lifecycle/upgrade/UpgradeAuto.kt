// NEW: auto-update (Marcos, Oct 13: "keep releasing to beta with auto update", and this machine follows beta).
//
// OPT-IN, OR NOTHING POLLS. UpgradeChecks keeps its law that a person's tap is the only way the console learns the newest
// release; this updater is a second, separate reader that runs only while `[daemon] auto_update = true`, read from
// splice.toml at every look, so turning it off needs no restart.
//
// IT ACTS ONLY ON A MANAGED INSTALL (UpgradeLayout.managedMarker): one install.sh or `splice upgrade` laid down. A jar a
// person or a build script copied into place is never replaced behind their back; the updater says so once and waits.
//
// WHAT IT DOES IS WHAT THE CONSOLE'S UPGRADE BUTTON DOES. It finds the newest published release on its channel, and when
// that is newer than the version this daemon serves it starts the same out-of-process run (UpgradeRuns), with --auto:
// the run checks every asset against the release's sha256sums.txt and not through gh's attestation, because a beta built
// while Actions is locked has none (splice-lead, Oct 13), and it restarts at the first moment no turn is in flight, or
// once the bounded wait is spent.
//
// ONE TRY PER VERSION PER DAEMON. A run that fails leaves this daemon serving, and the same version is not tried again
// until the daemon restarts or a newer one is published, so a release that cannot install does not loop.
//
// A REFUSED RELEASE STAYS REFUSED ACROSS RESTARTS (pre-merge review, Oct 13). A release the upgrade put back, or one
// `--rollback` left, is on UpgradeLayout.rejected in splice's state folder, and the put-back's own restart would
// otherwise empty the in-memory set above and try it again within the hour. It is skipped until a newer release is
// published, or a person installs it with `splice upgrade --to`, which never reads the list.
package splice.lifecycle.upgrade

import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// why: after a boot, the first look waits long enough for the daemon to settle and for a person who just restarted it
// to have done what they restarted for; a release published meanwhile waits that much longer, no more.
private const val FIRST_LOOK_MINUTES = 10L

// why: betas are cut through the day; an hour keeps a new one at most an hour away and stays far inside GitHub's
// unauthenticated limit of 60 API reads an hour.
private const val LOOK_EVERY_MINUTES = 60L

/** What splice.toml says now: whether the daemon installs newer releases itself, and whether betas count as newer. */
public data class AutoUpdatePolicy(val enabled: Boolean, val beta: Boolean)

/** Reads the policy from splice.toml at each look; null when the file cannot be read, which skips that look. */
public fun interface AutoUpdateSettings {
    public fun read(): AutoUpdatePolicy?
}

public class UpgradeAuto(
    env: EnvReader,
    private val runs: UpgradeRuns,
    private val settings: AutoUpdateSettings,
    private val log: LogSink,
) {
    private val layout = UpgradeLayout(env)
    private val channel = ReleaseChannel(env)
    private val tried = mutableSetOf<String>()
    private var saidUnmanaged = false

    /** Schedules the looks on one daemon thread, which lives as long as the daemon and never keeps the JVM up. */
    public fun start() {
        val looks = Executors.newSingleThreadScheduledExecutor { task ->
            Executors.defaultThreadFactory().newThread(task).apply {
                name = "splice-auto-update"
                isDaemon = true
            }
        }
        // A look that throws would cancel every later one, so each is folded into a logged line.
        val look = Runnable {
            Cancellables.runCatchingCancellable { look() }
                .onFailure { log("[auto-update] look failed: ${LogSafe.str(SafeFailureText.render(it))}\n") }
        }
        looks.scheduleWithFixedDelay(look, FIRST_LOOK_MINUTES, LOOK_EVERY_MINUTES, TimeUnit.MINUTES)
    }

    /** One look: the policy, the marker, the newest release on the channel, and a run when it is newer. */
    internal fun look() {
        val policy = settings.read()?.takeIf { it.enabled && managed() } ?: return
        val newest = newest(policy.beta)?.takeIf { ReleaseOrder(it) > ReleaseOrder(GATEWAY_VERSION) && tried.add(it) }
            ?: return
        if (layout.rejected.has(newest)) {
            log(
                "[auto-update] skipping ${LogSafe.str(newest)}: this machine put it back or rolled back from it; " +
                    "a newer release, or splice upgrade --to v${LogSafe.str(newest)}, installs past it\n",
            )
            return
        }
        upgrade(newest, if (policy.beta) "beta" else "stable")
    }

    /** Whether the marker is there; the first look that finds it absent says why nothing will happen. */
    private fun managed(): Boolean {
        val managed = layout.isManaged()
        if (!managed && !saidUnmanaged) {
            log(
                "[auto-update] on, but ${LogSafe.str(layout.managedMarker.toString())} is absent: " +
                    "install.sh or splice upgrade did not lay this install down\n",
            )
            saidUnmanaged = true
        }
        return managed
    }

    private fun upgrade(newest: String, channel: String) {
        log(
            "[auto-update] ${LogSafe.str(newest)} is newer than ${LogSafe.str(GATEWAY_VERSION)} " +
                "on the ${LogSafe.str(channel)} channel\n",
        )
        when (val started = runs.start(UpgradeRequest(to = "v$newest", rollback = false, auto = true))) {
            is UpgradeRunStart.Started -> log("[auto-update] run ${LogSafe.str(started.run.id)} started\n")
            UpgradeRunStart.Busy -> {
                tried.remove(newest)
                log("[auto-update] another upgrade is running; the next look tries again\n")
            }
            is UpgradeRunStart.Invalid -> log("[auto-update] refused: ${LogSafe.str(started.reason)}\n")
            is UpgradeRunStart.NotStarted -> log("[auto-update] did not start: ${LogSafe.str(started.reason)}\n")
        }
    }

    /** The newest release on the channel (ReleaseChannel), or null when it could not be read, which is logged. */
    private fun newest(beta: Boolean): String? = when (val read = channel.newest(beta)) {
        is Upgraded.Ok -> read.value
        is Upgraded.Refused -> null.also { log("[auto-update] ${LogSafe.str(read.reason)}\n") }
    }
}

/** SemVer 2.0.0 precedence over a normalized version (UpgradeLayout.isVersion): the numbers, then a release above any
 *  of its prereleases, then the prerelease identifiers, numeric ones below alphanumeric ones and compared as numbers. */
internal class ReleaseOrder(version: String) : Comparable<ReleaseOrder> {
    private val core = version.substringBefore('-').split('.').map { it.toLong() }
    private val pre = version.substringAfter('-', "").split('.').filter { it.isNotEmpty() }

    override fun compareTo(other: ReleaseOrder): Int {
        val numbers = core.zip(other.core).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a.compareTo(b) }
        return numbers ?: when {
            pre.isEmpty() || other.pre.isEmpty() -> other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
            else -> pre.zip(other.pre).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> identifier(a, b) }
                ?: pre.size.compareTo(other.pre.size)
        }
    }

    private fun identifier(a: String, b: String): Int {
        val x = a.toLongOrNull()
        val y = b.toLongOrNull()
        return when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b)
        }
    }
}
