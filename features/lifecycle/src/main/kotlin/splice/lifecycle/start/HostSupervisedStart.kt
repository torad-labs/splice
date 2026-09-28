// NEW: V4-395 — the shipped wiring of SupervisedStart, named so that a test can hold it to its promise: no
// cold start reaches a supervisor unit without the ownership check.
package splice.lifecycle.start

import splice.core.terminal.TerminalOutput
import splice.core.util.EnvReader
import splice.daemonclient.DaemonSettings

/** The real systemctl, a restarter with the long deadline, and the real [SystemdUnitDaemon]. */
internal object HostSupervisedStart {
    fun of(env: EnvReader, errors: TerminalOutput): SupervisedStart = SupervisedStart(
        JdkSystemctl(),
        env,
        DaemonSettings(errors),
        restarter = JdkSystemctl(UNIT_RESTART_TIMEOUT_MS),
        unitDaemon = SystemdUnitDaemon(DaemonSettings(errors)),
    )
}
