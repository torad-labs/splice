// NEW: V4-395 — whose a supervisor unit is. SupervisedStart.route refuses a shell that names its own daemon by
// an env selector, but a home that sets none (a second profile under its own HOME) reaches the everyday unit
// through it, and a restart then bounced a daemon that was nobody's business there (Marlin's walk, Sep 28,
// 12:40:45 PM CT: HOME=walk-desk, control_port 31210, restarted splice.service on :3096). The check compares
// the invoking home with the daemon the unit runs: the same HOME and the same control port, or it is foreign.
package splice.lifecycle.start

import splice.core.config.UserHome
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import java.nio.file.Path

/** The daemon a supervisor unit runs, as far as ownership goes: the HOME it lives under and the control port
 *  it resolves there. */
internal data class UnitDaemon(val home: Path, val controlPort: Int)

/** The [UnitDaemon] behind [unit], or null when its environment cannot be read. A test names a unit's daemon
 *  with a lambda; [SystemdUnitDaemon] asks the unit manager. */
internal fun interface UnitDaemonReader {
    operator fun invoke(unit: String): UnitDaemon?
}

/** Whose the unit is, for one command that wants to act on it. */
internal sealed class UnitOwnership {
    /** The unit's daemon is this shell's home's: the same HOME and the same control port. */
    data object Ours : UnitOwnership()

    /** The unit runs another home's daemon, or cannot be shown to run this one's; [reason] says which. */
    data class Foreign(val reason: String) : UnitOwnership()
}

/** Decides [UnitOwnership] for the shell whose environment is [envReader]. A null [unitDaemon] skips the
 *  check, which is the seam for a test that does not exercise it; [HostSupervisedStart] always supplies the
 *  real reader. */
internal class UnitOwner(
    private val unitDaemon: UnitDaemonReader?,
    private val envReader: EnvReader,
) {
    /** Whether [unit] runs the daemon this shell is about to act on, on [port]. A unit whose environment
     *  cannot be read is not shown to be ours, so it is not treated as ours. */
    fun of(unit: String, port: Int): UnitOwnership {
        val reader = unitDaemon ?: return UnitOwnership.Ours
        val theirs = reader(unit) ?: return UnitOwnership.Foreign(
            "its environment could not be read, so it cannot be shown to run this home's daemon",
        )
        val ours = UserHome.dir(envReader)
        val differences = listOfNotNull(
            "it runs the daemon of ${theirs.home}, and this shell's home is $ours"
                .takeUnless { sameDirectory(ours, theirs.home) },
            "it serves the control port :${theirs.controlPort}, and this home's is :$port"
                .takeIf { theirs.controlPort != port },
        )
        return if (differences.isEmpty()) UnitOwnership.Ours else UnitOwnership.Foreign(differences.joinToString("; "))
    }

    /** The same directory under a symlinked or unnormalized spelling: two homes named differently are not two
     *  homes. A path that does not resolve is compared as written. */
    private fun sameDirectory(a: Path, b: Path): Boolean = resolved(a) == resolved(b)

    private fun resolved(path: Path): Path =
        Cancellables.runCatchingCancellable { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }
}
