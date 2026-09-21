// NEW: V4-190 (2026-09-21) — the CLI's cold start is UNIT-FIRST, the same law the launch shim
// (app/src/main/dist/bin/splice-launch) carries since V4-189. `splice dashboard` and `splice restart` (and every verb that restarts
// through it: setup, add, the upgrade fallback) reach DaemonLaunch.ensureDaemon, and until this
// file that meant a raw `nohup java … daemon` whenever /health was down — which
// includes every second the supervisor unit spends restarting. Measured: 03:50:42 the unit's daemon
// stopped itself after a stalled turn path, a CLI verb from a Warp shell spawned pid 4138278 in that
// same second, and splice.service then lost the port to it five restarts running.
//
// The route is decided here so DaemonLaunch stays the composer: the unit when it exists on the box
// and no HARNESS SELECTOR points this shell at a daemon of its own; the raw spawn otherwise. The
// selector list is the shim's `unitDefaults()` byte for byte (SupervisedStartTest pins the two).
package splice.app.cli.daemon

import splice.app.cli.AdminSupport
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.util.concurrent.TimeUnit

/** The environment selectors that make a shell's daemon its own (a harness, a second install) —
 *  any of them set, even to whitespace, and the supervisor unit could not serve this shell. */
internal val harnessSelectors: List<String> = listOf(
    "SPLICE_CONFIG",
    "XDG_CONFIG_HOME",
    "SPLICE_JAR",
    "SPLICE_SHARE_DIR",
    "SPLICE_STATE_DIR",
    "CLAUDEX_STATE_DIR",
    "SPLICE_CONTROL_PORT",
    "CONTROL_PROXY_PORT",
    "CONTROL_PORT",
)

/** Where a cold start goes. */
internal sealed class ColdStartRoute {
    /** Start [unit] and wait for it; never spawn a daemon beside it. */
    data class Unit(val unit: String) : ColdStartRoute()

    /** Spawn here, for the [reason] named: no unit on the box, or a selector points this shell away. */
    data class Raw(val reason: String) : ColdStartRoute()
}

/** `systemctl --user <args>` as an exit code: 0 is yes. Its output is nobody's business here — a box
 *  with no user manager answers "Failed to connect to bus" on stderr, which the shim silences and the
 *  operator must not see as if it were the cold start's own failure. */
internal fun interface Systemctl {
    operator fun invoke(args: List<String>): Int
}

/** The real one: bounded, output discarded; a missing systemctl or a timeout is a non-zero answer. */
internal class JdkSystemctl(private val timeoutMs: Long = SYSTEMCTL_TIMEOUT_MS) : Systemctl {
    override fun invoke(args: List<String>): Int = Cancellables.runCatchingCancellable {
        val process = ProcessBuilder(listOf("systemctl", "--user") + args)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.exitValue()
        } else {
            process.destroyForcibly()
            SYSTEMCTL_TIMED_OUT
        }
    }.getOrElse { SYSTEMCTL_ABSENT }
}

internal class SupervisedStart(
    private val systemctl: Systemctl = JdkSystemctl(),
    private val envReader: EnvReader = EnvReader(System::getenv),
) {
    /** [unit] is the operator's SPLICE_SUPERVISOR_UNIT (default splice.service), resolved at the
     *  call so a diagnostic that never cold-starts never loads the topology for it. */
    fun route(unit: String = AdminSupport.supervisorUnit(envReader)): ColdStartRoute {
        val selector = harnessSelectors.firstOrNull { !envReader(it).isNullOrEmpty() }
        if (selector != null) {
            return ColdStartRoute.Raw("$selector is set, so this shell's daemon is its own, not $unit's")
        }
        // `cat` answers "the unit exists" without starting anything; a missing systemctl is exit 127.
        if (systemctl(listOf("cat", unit)) != 0) {
            return ColdStartRoute.Raw("no $unit on this machine")
        }
        return ColdStartRoute.Unit(unit)
    }

    /** `systemctl --user start [unit]`: true when systemd accepted the start job. Starting also
     *  cancels any pending restart backoff, so the unit comes back now rather than at its next tick. */
    fun start(unit: String): Boolean = systemctl(listOf("start", unit)) == 0
}

// why: `systemctl --user cat|start` answer in milliseconds; a manager that hangs longer than this is
// not one to wait on, and the raw route is the answer a box without a manager already gets.
private const val SYSTEMCTL_TIMEOUT_MS = 15_000L

// why: the shell's own code for a command that hit its deadline, so a log reader sees a familiar number.
private const val SYSTEMCTL_TIMED_OUT = 124

// why: the shell's own code for a command that is not there, so a log reader sees a familiar number.
private const val SYSTEMCTL_ABSENT = 127
