// NEW: desktop-door (docs/specs/desktop-door.md). The daemon's part of the door: the door itself, the switch
// `splice desktop` drives, and what `splice desktop` says. Built with the daemon and opened once its heads are up, on
// the control port, so the door's address is the same at every start. It is one door, at one path ([DOOR_PATH]) on
// the control port and on the share listener: Claude Desktop's Code tab is a caller of it as the terminal's `claude`
// and another machine are, with a key and a switch of its own and nothing else of its own. At each start, before the
// routes serve, what an older splice's `splice desktop on` wrote into `~/.claude/settings.json` is taken out
// (DesktopSwitch.tidy).
//
// door-default (docs/specs/door-default.md): the terminal's `claude` is the door's second caller (TerminalDoor), with a
// relay of its own. Decision 9: `claude` on its head hands the door's models to their heads (ClaudeHeadLane), through
// a relay of its own too.
package splice.app.desktop

import splice.app.control.ManagedHead
import splice.app.share.ManagedSharedHeads
import splice.client.desktop.DoorSelectors
import splice.client.models.AnthropicTiers
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.share.DOOR_SEGMENT
import splice.share.RefusalVoice
import splice.share.RelaySpeaker
import splice.share.ShareMachine
import splice.share.ShareRelay

internal class DaemonDesktop(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val statePaths: StatePaths,
    private val log: LogSink,
) {
    private val key = DesktopKey(statePaths.desktopKeyFile, log)
    private val records = DesktopRecords(statePaths.desktopFile, log)

    /** The terminal's `claude` on this door. */
    val claude = TerminalDoor(topology, statePaths, log)

    /** The door as another machine reaches it through the share listener (door-network). */
    val network = NetworkDoor()

    /** `claude` on its head, which hands the door's models to their heads (door-default, decision 9). */
    val headLane = ClaudeHeadLane(topology)

    /** The door and its switch once [open] has run; null before, which the routes answer as unavailable. */
    @Volatile
    var opened: DesktopService? = null
        private set

    /** The door on [controlPort], choosing among [heads]. */
    fun open(heads: Map<String, ManagedHead>, controlPort: Int) {
        val machine = ShareMachine(log).name()
        val running = RunningHeads {
            val relays = ManagedSharedHeads(heads, topology, config, machine).every()
            relays.mapNotNull { (headKey, relay) -> heads[headKey]?.let { it to relay } }
        }
        val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)
        val relay = ShareRelay(turnKey, machine, log, RelaySpeaker(voice, DESKTOP_TAG))
        val terminalVoice = RelaySpeaker(RefusalVoice(CLAUDE_ERROR_PREFIX), TERMINAL_TAG)
        val terminalRelay = ShareRelay(turnKey, machine, log, terminalVoice)
        val lineups = DesktopHeads(running)
        val door = "http://127.0.0.1:$controlPort$DOOR_PATH"
        // A selector, by the discovery prefixes of the heads in splice.toml; an Anthropic ID never is one, whatever a
        // prefix looks like, since it is the person's own pick.
        val tiers = AnthropicTiers()
        val selectors = DoorSelectors { model ->
            tiers.tierOf(model) == null &&
                topology.heads.values.any { it.discoveryPrefix.isNotEmpty() && model.startsWith(it.discoveryPrefix) }
        }
        val (lane, terminal) = claude.open(terminalRelay, lineups, door)
        val switch = DesktopSwitch(records, key, DesktopSettingsFile(), lane, selectors, log)
        switch.tidy()
        val headLaneVoice = RelaySpeaker(terminalVoice.errors, HEAD_LANE_TAG)
        headLane.open(ShareRelay(turnKey, machine, log, headLaneVoice), lineups)
        val callers = listOf(DesktopCaller(relay, key, records, log), terminal)
        val doorway = DesktopDoor(callers, lineups, lane)
        val networkVoice = RelaySpeaker(RefusalVoice(NETWORK_ERROR_PREFIX), NETWORK_TAG)
        val networkRelay = ShareRelay(turnKey, machine, log, networkVoice)
        network.open(doorway, NetworkCaller(networkRelay, lane, machine), lane, PickerRows(claude::rows), machine)
        opened = DesktopService(doorway, switch, lane, lineups, records)
    }
}

/** Where the door is answered on the control port: the share listener's path for it, so a machine reaches the same
 *  door at the same path from itself and from another machine. */
internal const val DOOR_PATH = "/$DOOR_SEGMENT"

/** Where the control port answered the door before 0.6.4, which it still answers: a Desktop set up then holds this
 *  path in its environment store, where splice cannot write, and a `claude` session started then runs on it. */
internal const val DESKTOP_PATH = "/desktop"

// The tag the relay's log lines carry for a door turn.
private const val DESKTOP_TAG = "desktop"

// The tag the relay's log lines carry for a turn of the terminal's `claude`.
private const val TERMINAL_TAG = "terminal"

// The tag the relay's log lines carry for a turn from another machine (door-network).
private const val NETWORK_TAG = "network"

// The tag the relay's log lines carry for a turn `claude` on its head hands to another head.
private const val HEAD_LANE_TAG = "claude"
