// NEW: door-default (docs/specs/door-default.md, decisions 1 and 3). The terminal's `claude` as the door's second
// caller: its key, its lane record and the rule that reads it, kept apart from Desktop's so Desktop's switch and key
// leave it as it is; and, once the door opens, its lane, its caller and its launch. Split from DaemonDesktop, which
// builds the door both callers share.
package splice.app.desktop.lane

import splice.app.desktop.DoorCaller
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.client.desktop.DoorPickerRow
import splice.client.login.DoorLogin
import splice.core.config.FileHeldKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.mcp.McpUserRegistry
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.launch.recipe.ClaudeDoor
import splice.launch.recipe.DoorLaunchSpec
import splice.launch.recipe.GatewayAt
import splice.launch.recipe.ProfileLaunch
import splice.upstream.codemode.ProcessDispatchers

internal class TerminalDoor(
    private val topology: Topology,
    statePaths: StatePaths,
    private val log: LogSink,
) : ClaudeDoor {
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
    private val profiles = TerminalProfiles(
        topology.launch.profiles,
        UserHome.dir(),
        statePaths.launchProfilesDir,
        McpUserRegistry(statePaths),
        ProcessDispatchers().io(),
    )

    /** `claude`'s launch once [open] has run; null before. */
    @Volatile
    private var launched: ClaudeDoorLaunch? = null

    /** The lane on [providers] at [door], and its caller. */
    fun open(providers: DesktopProviders, door: String): Pair<ClaudeLane, DoorCaller> {
        val lane = ClaudeLane(records, key, providers, door, claudeHead, ClaudeLogin(login, log))
        launched = ClaudeDoorLaunch(lane, login, log)
        return lane to TerminalCaller(lane)
    }

    /** `claude`'s launch on the door; null before [open] and on the head lane. */
    override suspend fun launch(): DoorLaunchSpec? = launched?.launch()

    /** Side-by-side launch (`splice launch claude` / `claude-splice`); always on the door once [open] has run. */
    override suspend fun launchSideBySide(): DoorLaunchSpec? = launched?.launchSideBySide()

    /** `splice launch claude --profile <name>`: the side-by-side launch over the profile's folder, rebuilt first. */
    override suspend fun launchProfile(name: String): ProfileLaunch {
        val side = launched?.launchSideBySide() ?: return ProfileLaunch.Refused(DOOR_CLOSED)
        return profiles.launch(name, side)
    }

    /** A launch of `[door.commands.<name>]` on the door; null for a name splice.toml gives no door command, and before
     *  [open]. */
    override suspend fun launchCommand(name: String): DoorLaunchSpec? =
        topology.door.commands[name]?.let { command ->
            launched?.launchCommand(command.providerKey(topology.heads))
        }

    /** Whether `claude` is on the door: read from the lane record, so it holds before [open] too. */
    override fun onDoor(): Boolean = records.lane(claudeHead) == DOOR_LANE

    /** The Splice gateway [head]'s command points its session at, on either lane; null before [open]. */
    override suspend fun gateway(head: String): GatewayAt? =
        launched?.gateway(topology.heads[head]?.provider ?: head)

    override fun headHere(): Boolean = claudeHead()

    /** The picker's rows of [lineup] as a launch of `claude` on the door lists them; none before [open]. */
    fun rows(lineup: DesktopLineup): List<DoorPickerRow> = launched?.rowsOf(lineup).orEmpty()
}

// What the terminal's key starts with, so it is told from Desktop's at a glance.
private const val TERMINAL_KEY_PREFIX = "splice-terminal-"

// What a profile's launch is told before the door has opened.
private const val DOOR_CLOSED = "splice: the door is not open yet; try again in a moment"
