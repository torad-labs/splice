// NEW: desktop-door (docs/specs/desktop-door.md). The daemon's part of Claude Desktop's door: the door itself, the
// switch `splice desktop` drives, and what `splice desktop` says. Built with the daemon and opened once its heads are
// up, on the control port, so the door's address is the same at every start. At each start, before the routes serve,
// what an older splice's `splice desktop on` wrote into `~/.claude/settings.json` is taken out (DesktopSwitch.tidy).
//
// door-default (docs/specs/door-default.md): the terminal's `claude` is the door's second caller (TerminalDoor), with a
// relay of its own. Decision 9: `claude` on its head hands the door's models to their heads (ClaudeHeadLane), through
// a relay of its own too.
package splice.app.desktop

import splice.app.control.ManagedHead
import splice.app.provider.CredentialChanged
import splice.app.provider.ModelRosters
import splice.app.share.ManagedSharedHeads
import splice.client.desktop.DoorSelectors
import splice.client.models.AnthropicTiers
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.LogSink
import splice.share.RefusalVoice
import splice.share.RelaySpeaker
import splice.share.ShareMachine
import splice.share.ShareRelay
import splice.upstream.codemode.ProcessDispatchers

internal class DaemonDesktop(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val statePaths: StatePaths,
    private val log: LogSink,
    /** Where Claude Desktop keeps its 3P configuration (desktop-full): the person's own, but a test's fixture home. */
    private val library: DesktopAppLibrary = DesktopAppLibrary(),
) {
    private val key = DesktopKey(statePaths.desktopKeyFile, log)
    private val records = DesktopRecords(statePaths.desktopFile, log)

    /** What splice changes in Claude Desktop: its configuration in Desktop's library and the helper that names. */
    private val app = DesktopAppMode(records, library, DesktopCredentialHelper(statePaths.stateDir))

    /** The terminal's `claude` on this door. */
    val claude = TerminalDoor(topology, statePaths, log)

    /** `claude` on its head, which hands the door's models to their heads (door-default, decision 9). */
    val headLane = ClaudeHeadLane(topology)

    /** The door and its switch once [open] has run; null before, which the routes answer as unavailable. */
    @Volatile
    var opened: DesktopService? = null
        private set

    /** The door's heads once [open] has run; null before. */
    @Volatile
    private var lineups: DesktopHeads? = null

    /** The door on [controlPort], choosing among [heads], with the model catalog of the providers signed in now
     *  published before any route serves it, and again whenever [rosters] ask a head's list with a new credential. */
    suspend fun open(heads: Map<String, ManagedHead>, controlPort: Int, rosters: ModelRosters) {
        val machine = ShareMachine(log).name()
        val running = RunningHeads {
            val relays = ManagedSharedHeads(heads, topology, config, machine).every()
            relays.mapNotNull { (headKey, relay) -> heads[headKey]?.let { it to relay } }
        }
        val voice = RefusalVoice(DESKTOP_ERROR_PREFIX)
        val io = ProcessDispatchers().io()
        val relay = ShareRelay(turnKey, controlPort, machine, io, log, RelaySpeaker(voice, DESKTOP_TAG))
        val terminalVoice = RelaySpeaker(RefusalVoice(CLAUDE_ERROR_PREFIX), TERMINAL_TAG)
        val terminalRelay = ShareRelay(turnKey, controlPort, machine, io, log, terminalVoice)
        val catalog = PublishedCatalog(CatalogBuild(), CatalogFile(statePaths.modelCatalogFile, log), io)
        val lineups = DesktopHeads(running, topology.heads.mapValues { it.value.provider }, catalog)
        this.lineups = lineups
        rosters.credentialChanged = CredentialChanged { republish() }
        lineups.publish()
        val door = "http://127.0.0.1:$controlPort$DOOR_PATH"
        // A selector, by the discovery prefixes of the heads in splice.toml; an Anthropic ID never is one, whatever a
        // prefix looks like, since it is the person's own pick.
        val tiers = AnthropicTiers()
        val selectors = DoorSelectors { model ->
            tiers.tierOf(model) == null &&
                topology.heads.values.any { it.discoveryPrefix.isNotEmpty() && model.startsWith(it.discoveryPrefix) }
        }
        val tidy = DesktopSettingsTidy(DesktopSettingsFile(), records, selectors, log)
        val switch = DesktopSwitch(lineups, records, key, door, app, tidy, log)
        switch.tidy()
        val (lane, terminal) = claude.open(terminalRelay, lineups, door)
        val headLaneVoice = RelaySpeaker(terminalVoice.errors, HEAD_LANE_TAG)
        headLane.open(ShareRelay(turnKey, controlPort, machine, io, log, headLaneVoice), lineups)
        val callers = listOf(DesktopCaller(relay, key, records, log), terminal)
        opened = DesktopService(DesktopDoor(callers, lineups), switch, lane, lineups, records)
    }

    /** The model catalog built again from the providers signed in now and served from here on: a head's credential
     *  changed (a sign-in, a sign-out, a key set). Nothing before [open]. */
    suspend fun republish() {
        lineups?.publish()
    }
}

/** Where the door is answered on the control port. */
internal const val DOOR_PATH = "/door"

/** Where the door was answered before it had two callers; it still is, for a Desktop set up then. */
internal const val DESKTOP_PATH = "/desktop"

// The tag the relay's log lines carry for a door turn.
private const val DESKTOP_TAG = "desktop"

// The tag the relay's log lines carry for a turn of the terminal's `claude`.
private const val TERMINAL_TAG = "terminal"

// The tag the relay's log lines carry for a turn `claude` on its head hands to another head.
private const val HEAD_LANE_TAG = "claude"
