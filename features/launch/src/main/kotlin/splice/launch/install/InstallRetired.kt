// NEW: head-catalog (gw-retire). The commands `splice claude retire` retired, which no install lays again: `splice
// install`, `splice setup` and an upgrade all link through InstallLinker, and each leaves a retired command out and says
// so. `splice claude restore` is the one way back. The record is splice's state (CommandRetirements), read where the
// daemon keeps it.
package splice.launch.install

import splice.core.retired.CommandRetirements
import splice.core.terminal.TerminalOutput
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.topology.TopologyStatePaths

internal class InstallRetired(private val output: TerminalOutput) {
    /** [selected], each a head's key with its command, less the retired ones, each said. */
    fun kept(selected: List<Pair<String, String>>, topology: Topology, env: EnvReader): List<Pair<String, String>> {
        val retired = CommandRetirements(TopologyStatePaths(env).of(topology).stateDir).all().map { it.key }.toSet()
        val (kept, left) = selected.partition { (key, _) -> key !in retired }
        left.forEach { (_, command) ->
            output.line(
                "splice: '$command' is retired, so it is not linked; " +
                    "`splice claude restore $command` brings it back",
            )
        }
        return kept
    }
}
