// NEW: the Settings Version card's "Check" (splice-lead, 2026-10-12) — one tap learns the newest published release.
//
// THE ONLY WAY LATEST IS EVER LEARNED IS A TAP. No poll, no schedule: [check] runs when POST /api/upgrade/check calls it,
// and [snapshot] only reports what the last tap found. A tap reads the release the way `splice upgrade` does (the
// published sums, the launch shim checked against them, [UpgradeRelease.latest]) and sends no credential of splice's own:
// when `gh` is signed in it also runs `gh attestation verify` on the shim, with gh's login, as the upgrade does.
//
// A FAILED READ REPLACES THE SNAPSHOT WITH "not read", never keeps an older answer: a number that was true last week and
// is shown beside today's date is a guess wearing a measurement's clothes.
//
// TAPS CANNOT OVERLAP OR REPEAT QUICKLY. One read at a time ([UpgradeCheckOutcome.Busy]), and a new one no sooner than
// [MIN_GAP_MS] after the last began ([UpgradeCheckOutcome.TooSoon]), so a held-down button is not a request flood
// aimed at a remote host.
package splice.lifecycle.upgrade

import splice.core.terminal.TerminalOutput
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.deleteRecursively

/** Shortest time between the starts of two reads. */
private const val MIN_GAP_MS = 10_000L
private const val CHECK_STAGING_PREFIX = "splice-upgrade-check-"

/** What the last tap found: [NEVER] before any tap, [READ] with the newest version, [NOT_READ] when the fetch failed. */
public enum class UpgradeReadState(public val wire: String) {
    NEVER("never_checked"),
    READ("read"),
    NOT_READ("not_read"),
}

/** [latest] and [readAtMillis] are set only for [UpgradeReadState.READ]; [reason] only for [UpgradeReadState.NOT_READ]. */
public data class UpgradeSnapshot(
    val state: UpgradeReadState,
    val latest: String?,
    val readAtMillis: Long?,
    val reason: String?,
)

public sealed class UpgradeCheckOutcome {
    public class Done(public val snapshot: UpgradeSnapshot) : UpgradeCheckOutcome()

    public data object Busy : UpgradeCheckOutcome()

    public class TooSoon(public val retryAfterSeconds: Long) : UpgradeCheckOutcome()
}

/** [settings] names the channel at each read: on beta the newest release comes from the releases list the daemon's
 *  updater reads (ReleaseChannel), prereleases included, so the Check, `splice upgrade --check` and auto-update agree. */
public class UpgradeChecks(
    private val env: EnvReader,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val settings: AutoUpdateSettings = AutoUpdateSettings { null },
) {
    /** The release reader. Internal and replaceable so a test puts its fake at the network edge; production never touches it. */
    internal var release: UpgradeRelease =
        UpgradeRelease(TerminalOutput { }, JdkUpgradeFetch(), JdkUpgradeProcess(), "java")

    internal var channel: ReleaseChannel = ReleaseChannel(env)

    private val last = AtomicReference(UpgradeSnapshot(UpgradeReadState.NEVER, null, null, null))
    private val running = AtomicBoolean(false)
    private var lastStartedAt: Long? = null

    /** What the last tap found; never fetches. */
    public fun snapshot(): UpgradeSnapshot = last.get()

    /** One read, blocking; the caller keeps it off its request threads. */
    public fun check(): UpgradeCheckOutcome {
        if (!running.compareAndSet(false, true)) return UpgradeCheckOutcome.Busy
        try {
            val now = clock()
            val wait = lastStartedAt?.let { it + MIN_GAP_MS - now } ?: 0L
            if (wait > 0) return UpgradeCheckOutcome.TooSoon(TimeUnit.MILLISECONDS.toSeconds(wait - 1) + 1)
            lastStartedAt = now
            return UpgradeCheckOutcome.Done(read(now).also { last.set(it) })
        } finally {
            running.set(false)
        }
    }

    private fun read(startedAt: Long): UpgradeSnapshot {
        val staging = Files.createTempDirectory(CHECK_STAGING_PREFIX)
        val beta = settings.read()?.beta == true
        val answer = Cancellables.runCatchingCancellable {
            if (beta) {
                channel.newest(beta = true)
            } else {
                release.latest(release.base(null, env("SPLICE_RELEASE_BASE_URL")), staging)
            }
        }
        discard(staging)
        val failure = answer.exceptionOrNull()
        return when (val read = answer.getOrNull()) {
            is Upgraded.Ok -> UpgradeSnapshot(UpgradeReadState.READ, read.value, startedAt, null)
            is Upgraded.Refused -> notRead(read.reason)
            null -> notRead(failure?.let { SafeFailureText.render(it) } ?: "the read did not finish")
        }
    }

    private fun notRead(reason: String) = UpgradeSnapshot(UpgradeReadState.NOT_READ, null, null, reason)

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun discard(path: Path) {
        if (Files.exists(path)) path.deleteRecursively()
    }
}
