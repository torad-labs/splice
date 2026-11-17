// NEW: door-default (docs/specs/door-default.md, decisions 1 and 7). The lane `claude` is on, read where splice keeps
// it by a command that runs with no daemon: `splice status` and `splice doctor` say it, and on its head, that heads
// are deprecated and how to move. The rule is the daemon's own (ClaudeLaneRecords.lane): the lane chosen, or with no
// choice made, its head while splice.toml has one and the door otherwise.
package splice.app.desktop

import splice.core.config.StatePaths
import splice.core.topology.CLAUDE_COMMAND
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.diagnostics.doctor.ClaudeLaneReading
import splice.launch.recipe.HEADS_DEPRECATED

/** Whether [topology] has a head `claude` can go back to: one whose command is `claude`, or a `claude` another machine
 *  serves. */
internal class TopologyClaudeHead(private val topology: Topology) : ClaudeHeadHere {
    override fun invoke(): Boolean =
        topology.resolveHeadKeys(CLAUDE_COMMAND).isNotEmpty() || topology.remote.containsKey(CLAUDE_COMMAND)
}

internal class ClaudeLaneLook(topology: Topology, statePaths: StatePaths, log: LogSink) {
    private val records = ClaudeLaneRecords(statePaths.claudeLaneFile, log)
    private val headHere = TopologyClaudeHead(topology)

    fun reading(): ClaudeLaneReading =
        if (records.lane(headHere) == DOOR_LANE) {
            ClaudeLaneReading(onDoor = true, said = RUNS_ON_DOOR)
        } else {
            ClaudeLaneReading(onDoor = false, said = RUNS_ON_HEAD, fix = HEADS_DEPRECATED)
        }

    /** The line `splice status` prints. */
    fun line(): String = reading().let { listOfNotNull(it.said, it.fix).joinToString("; ") }
}

private const val RUNS_ON_DOOR = "claude runs on splice's door"
private const val RUNS_ON_HEAD = "claude runs on its head"
