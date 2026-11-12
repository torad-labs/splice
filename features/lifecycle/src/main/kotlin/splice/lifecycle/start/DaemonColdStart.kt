// NEW: Oct 12, 2026 — the one public entry of the cold start, shared by `splice dashboard` and `splice restart`.
// Split from DaemonLaunch, which keeps the argv and the ensureDaemon composer it delegates to.
package splice.lifecycle.start

import splice.core.GATEWAY_VERSION
import splice.core.config.RunningJar
import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonHealth

/** The daemon cold start `splice dashboard` and `splice restart` share: the supervisor unit when this
 *  box has one, the raw spawn otherwise, then the wait for the expected version. [errors] carries the
 *  corrupt-TOML diagnostic the supervisor-unit read can raise; stdout belongs to the verb. [supervised]
 *  and [startupPolls] keep their defaults outside this module's tests. */
public class DaemonColdStart(
    output: TerminalOutput,
    errors: TerminalOutput,
    env: EnvReader,
    jar: RunningJar,
    supervised: SupervisedStart = HostSupervisedStart.of(env, errors),
    startupPolls: Int = STARTUP_POLLS,
) {

    private val launch = DaemonHealth().let { health ->
        DaemonLaunch(output, health, DaemonSpawn(output, health, jar), supervised, startupPolls)
    }

    /** Cold-start the daemon detached (survives this CLI exiting) and wait until it answers with
     *  [expectedVersion] (this CLI's own, or the one an upgrade just activated). */
    public fun ensureDaemon(port: Int, expectedVersion: String = GATEWAY_VERSION): Boolean =
        launch.ensureDaemon(port, expectedVersion)

    /** V4-243: the supervisor unit a restart must go THROUGH, or null when this shell's daemon is its
     *  own (a selector is set), there is no unit on the box, or the unit is not running a daemon. */
    public fun activeUnit(): String? = launch.activeUnit()

    /** The supervisor unit a start of this shell's daemon goes through, running or not, or null when
     *  this shell's daemon is its own (a selector is set) or there is no unit on the box. */
    public fun supervisorUnit(): String? = launch.supervisorUnit()

    /** `splice start`: the exit code once the supervisor unit has the daemon answering on [port], as every
     *  cold start does where a unit serves this shell (V4-190), running or not; null where none does, and
     *  the daemon runs in the calling process. Oct 1, 11:49 PM CT: a `start` typed while splice.service
     *  restarted took its port, with no heap cap and nothing to restart it. */
    public fun startThroughUnit(port: Int): Int? {
        supervisorUnit() ?: return null
        return if (ensureDaemon(port)) 0 else 1
    }

    /** V4-395: what a restart says when the supervisor unit runs another home's daemon (or cannot be shown
     *  to run this one's) on [port], or null when the unit is this home's or this shell's daemon is its own.
     *  A restart that gets a sentence stops there: nothing is signalled and no unit verb is run. */
    public fun foreignUnitRefusal(port: Int): String? = launch.foreignUnitRefusal(port)

    /** V4-243: restart [unit] through systemd and wait until the daemon answers with [expectedVersion]. */
    public fun restartUnit(unit: String, port: Int, expectedVersion: String = GATEWAY_VERSION): Boolean =
        launch.restartUnit(unit, port, expectedVersion)
}
