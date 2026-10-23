// NEW: how `splice restart` reads splice.toml before it stops anything. Split from RestartCommand so the verb keeps
// its own decisions (stop, start, wait) and this file keeps the one about a file that cannot serve.
package splice.lifecycle.restart

import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.SafeFailureText
import splice.core.util.TopologyRefusal
import splice.topology.LocalRowFindings
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
    private val repair: ConfigRepair,
    private val runtimes: LocalRowFindings,
) {
    /** The topology, or null when the restart must not proceed. The daemon that comes back reads this same file
     *  before it boots, so a file this CLI cannot read is one no successor can boot on: nothing is stopped. A
     *  splice.toml this build refuses prints every finding and, on a terminal, offers the fix session and reads the
     *  file again, so `splice restart` repairs and proceeds in one go. A missing file is no failure: the read writes
     *  the starter, as boot does. */
    fun read(): Topology? {
        val path = TopologyLoader.configPath(env)
        return Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path, runtimes).topology }.fold(
            onSuccess = { it },
            onFailure = { failure ->
                output.line("splice: ${SafeFailureText.render(failure)}")
                if (failure is TopologyRefusal) repaired(path) else unreadable(path)
            },
        )
    }

    /** A splice.toml this build refuses: nothing is stopped, and on a terminal the fix session is offered and the file
     *  read again, so one `splice restart` repairs and proceeds. Null when the restart must not go on. */
    private fun repaired(path: Path): Topology? {
        output.line("splice: nothing was stopped and $path is unchanged")
        if (!repair.offer()) return null
        return Cancellables.runCatchingCancellable { TopologyLoader.loadForBoot(path, runtimes).topology }
            .onFailure { output.line("splice: ${SafeFailureText.render(it)}") }
            .getOrNull()
    }

    /** A splice.toml that exists but cannot be read (its permissions, a dangling link): the running daemon could still
     *  name its head ports, but the daemon started after it reads this file first and would not boot, so stopping the
     *  one that serves would leave every head down. */
    private fun unreadable(path: Path): Topology? {
        output.line("splice: nothing was stopped: the daemon reads $path before it boots, so fix that file and retry")
        return null
    }
}
