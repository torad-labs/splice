// NEW: how `splice restart` reads splice.toml before it stops anything. Split from RestartCommand so the verb keeps
// its own decisions (stop, start, wait) and this file keeps the one about a file that cannot serve.
package splice.lifecycle.restart

import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.daemonclient.DaemonProbe
import splice.daemonclient.DaemonSettings
import splice.topology.TopologyLoader
import java.nio.file.Path

/** The fix session a CLI offers for a splice.toml that cannot serve: true when every finding was fixed, so the
 *  caller may read the file again. The default offers nothing, which is what a non-interactive caller wants; :app
 *  wires the real session, which lives there with the terminal. */
public fun interface ConfigRepair {
    public fun offer(): Boolean
}

internal class RestartTopology(
    private val output: TerminalOutput,
    private val env: EnvReader,
    private val settings: DaemonSettings,
    private val repair: ConfigRepair,
) {
    /** The topology, as a Result that holds null when the file could not be read but a running daemon can still answer
     *  for the head ports; null when the restart must not proceed at all. A splice.toml this build refuses is the
     *  second case: nothing is stopped, every finding is printed, and on a terminal the fix session is offered and the
     *  file read again, so `splice restart` repairs and proceeds in one go. */
    fun read(): Result<Topology?>? {
        val path = TopologyLoader.configPath(env)
        val read = Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path).topology }
        val failure = read.exceptionOrNull() ?: return read
        output.line("splice: ${SafeFailureText.render(failure)}")
        return if (failure is TopologyRefusal) repaired(path) else withRunningDaemon(path)
    }

    /** A splice.toml this build refuses: nothing is stopped, and on a terminal the fix session is offered and the file
     *  read again, so one `splice restart` repairs and proceeds. Null when the restart must not go on. */
    private fun repaired(path: Path): Result<Topology?>? {
        output.line("splice: nothing was stopped and $path is unchanged")
        if (!repair.offer()) return null
        val again = Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path).topology }
            .onFailure { output.line("splice: ${SafeFailureText.render(it)}") }
        return if (again.isFailure) null else again
    }

    /** An unreadable file is not a refusal: a daemon that is up still answers for the head ports. */
    private fun withRunningDaemon(path: Path): Result<Topology?>? {
        if (DaemonProbe.healthVersion(settings.controlPort(null, env)) != null) {
            output.line("splice: falling back to the running daemon for head ports")
            return Result.success(null)
        }
        output.line("splice: cannot start the daemon until $path is fixed")
        return null
    }
}
