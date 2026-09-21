// NEW: V4-190 (2026-09-21) — the CLI's cold start is UNIT-FIRST, the same law bin/splice-launch
// carries since V4-189. `splice status|dashboard|restart|doctor` reach DaemonLaunch.ensureDaemon,
// and until this file that meant a raw `nohup java … daemon` whenever /health was down — which
// includes every second the supervisor unit spends restarting. Measured: 03:50:42 the unit's daemon
// stopped itself after a stalled turn path, a CLI verb from a Warp shell spawned pid 4138278 in that
// same second, and splice.service then lost the port to it five restarts running.
//
// The route is decided here so DaemonLaunch stays the composer: the unit when it exists on the box
// and no HARNESS SELECTOR points this shell at a daemon of its own; the raw spawn otherwise. The
// selector list is the shim's `unit_defaults()` byte for byte (SupervisedStartTest pins the two).
package splice.app.cli.daemon

import splice.app.cli.AdminSupport
import splice.app.cli.upgrade.JdkUpgradeProcess
import splice.app.cli.upgrade.UpgradeProcess
import splice.core.util.EnvReader

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

internal class SupervisedStart(
    private val process: UpgradeProcess = JdkUpgradeProcess(),
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
        if (process(listOf("systemctl", "--user", "cat", unit), false).code != 0) {
            return ColdStartRoute.Raw("no $unit on this machine")
        }
        return ColdStartRoute.Unit(unit)
    }

    /** `systemctl --user start [unit]`: true when systemd accepted the start job. Starting also
     *  cancels any pending restart backoff, so the unit comes back now rather than at its next tick. */
    fun start(unit: String): Boolean = process(listOf("systemctl", "--user", "start", unit), false).code == 0
}
