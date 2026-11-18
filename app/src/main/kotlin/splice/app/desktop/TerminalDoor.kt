// NEW: door-default (docs/specs/door-default.md, decisions 1 and 3). The terminal's `claude` as the door's second
// caller: its key, its lane record and the rule that reads it, kept apart from Desktop's so Desktop's switch and key
// leave it as it is; and, once the door opens, its lane, its caller and its launch. Split from DaemonDesktop, which
// builds the door both callers share.
package splice.app.desktop

import splice.client.desktop.DoorPicker
import splice.client.login.DoorLogin
import splice.core.config.FileHeldKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.launch.recipe.ClaudeDoor
import splice.launch.recipe.DoorLaunchSpec
import splice.share.ShareRelay

internal class TerminalDoor(topology: Topology, statePaths: StatePaths, private val log: LogSink) : ClaudeDoor {
    private val key = FileHeldKey(
        file = statePaths.terminalKeyFile,
        prefix = TERMINAL_KEY_PREFIX,
        tag = "claude",
        consequence = "a `claude` session started before is refused at the door; a new one carries the new key",
        log = log,
    )
    private val records = ClaudeLaneRecords(statePaths.claudeLaneFile, log)
    private val claudeHead = TopologyClaudeHead(topology)
    private val login = DoorLogin(UserHome.claudeDir(), statePaths.doorLoginDir)

    /** `claude`'s launch once [open] has run; null before. */
    @Volatile
    private var launched: ClaudeDoorLaunch? = null

    /** The lane on [heads] at [door], and its caller, relaying through [relay]. */
    fun open(relay: ShareRelay, heads: DesktopHeads, door: String): Pair<ClaudeLane, DoorCaller> {
        val lane = ClaudeLane(records, key, heads, door, claudeHead, ClaudeLogin(login, log))
        launched = ClaudeDoorLaunch(lane, login, log)
        return lane to TerminalCaller(relay, lane)
    }

    /** `claude`'s launch on the door; null before [open] and on the head lane. */
    override suspend fun launch(): DoorLaunchSpec? = launched?.launch()

    /** Whether `claude` is on the door: read from the lane record, so it holds before [open] too. */
    override fun onDoor(): Boolean = records.lane(claudeHead) == DOOR_LANE

    /** The door's rows a launch of `claude` on its head adds after Claude Code's own (decision 9); null before [open],
     *  on the door, and with no account signed in. */
    override suspend fun headPicker(): DoorPicker? = launched?.headPicker()

    override fun headHere(): Boolean = claudeHead()
}

// What the terminal's key starts with, so it is told from Desktop's at a glance.
private const val TERMINAL_KEY_PREFIX = "splice-terminal-"
