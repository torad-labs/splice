// NEW: desktop-door (docs/specs/desktop-door.md); gateway-no-heads. The daemon's part of Claude Desktop's door: the
// door itself, the switch `splice desktop` drives, and what `splice desktop` says. Built with the daemon and opened
// once its providers are up, on the control port, so the door's address is the same at every start. At each start,
// before the routes serve, what an older splice's `splice desktop on` wrote into `~/.claude/settings.json` is taken out
// (DesktopSwitch.tidy).
//
// The door serves every provider in splice.toml itself: a turn is handed to the provider's own serving, and no head is
// read or relayed to. door-default (docs/specs/door-default.md): the terminal's `claude` is the door's second caller
// (TerminalDoor). Decision 9: `claude` on its head hands the door's models to the door (ClaudeHeadLane).
package splice.app.desktop

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splice.app.desktop.catalog.CatalogBuild
import splice.app.desktop.catalog.CatalogFile
import splice.app.desktop.catalog.DesktopLineup
import splice.app.desktop.catalog.DesktopProviders
import splice.app.desktop.catalog.DoorCatalog
import splice.app.desktop.catalog.PublishedCatalog
import splice.app.desktop.catalog.SelectorAliases
import splice.app.desktop.lane.ClaudeHeadLane
import splice.app.desktop.lane.TerminalDoor
import splice.app.desktop.record.DesktopKey
import splice.app.desktop.record.DesktopRecords
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopAppMode
import splice.app.desktop.setup.DesktopPrograms
import splice.app.desktop.setup.DesktopSettingsFile
import splice.app.desktop.setup.DesktopSettingsTidy
import splice.app.desktop.setup.DesktopSwitch
import splice.app.provider.CredentialChanged
import splice.app.provider.ModelRosters
import splice.app.provider.ServingProviders
import splice.client.desktop.DoorSelectors
import splice.client.models.AnthropicTiers
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.share.ShareMachine
import splice.upstream.codemode.ProcessDispatchers

internal class DaemonDesktop(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val log: LogSink,
    /** Where Claude Desktop keeps its 3P configuration (desktop-full): the person's own, but a test's fixture home. */
    private val library: DesktopAppLibrary = DesktopAppLibrary(),
) {
    private val key = DesktopKey(statePaths.desktopKeyFile, log)
    private val records = DesktopRecords(statePaths.desktopFile, log)

    /** What splice changes in Claude Desktop: its configuration in Desktop's library and the helper that names. */
    private val app = DesktopAppMode(records, library, DesktopPrograms(statePaths.stateDir))

    /** The terminal's `claude` on this door. */
    val claude = TerminalDoor(topology, statePaths, log)

    /** The door as another machine reaches it through the share listener (door-network). */
    val network = NetworkDoor()

    /** The door as a keyed team reaches it through the team listener (gateway-no-heads). */
    val team = TeamDoor()

    /** What reads an old selector as a provider's: the gateway's own table, built from splice.toml. */
    private val aliases =
        SelectorAliases(topology.providers.keys, topology.door.selectorPrefixes, topology.heads.values)

    /** `claude` on its head, which hands the door's models to the door (door-default, decision 9). */
    val headLane = ClaudeHeadLane(topology, aliases)

    /** The door and its switch once [open] has run; null before, which the routes answer as unavailable. */
    @Volatile
    var opened: DesktopService? = null
        private set

    /** The door's providers once [open] has run; null before. */
    @Volatile
    private var lineups: DesktopProviders? = null

    @Volatile
    private var door: String? = null
    private val io = ProcessDispatchers().io()
    private val publishing = Mutex()

    /** The door on [controlPort], serving [providers], with the model catalog of the providers signed in now published
     *  before any route serves it, and again whenever [rosters] ask a provider's list with a new credential. Claude
     *  Desktop's lists also carry [desktopOnly]'s models, which only its launcher runs (desktop-antigravity). */
    suspend fun open(
        providers: ServingProviders,
        desktopOnly: ServingProviders,
        controlPort: Int,
        rosters: ModelRosters,
    ) {
        val machine = ShareMachine(log).name()
        val catalog = PublishedCatalog(CatalogBuild(), CatalogFile(statePaths.modelCatalogFile, log), io)
        val lineups = DesktopProviders(providers, desktopOnly, aliases, catalog)
        this.lineups = lineups
        rosters.credentialChanged = CredentialChanged { republish() }
        lineups.publish()
        val door = "http://127.0.0.1:$controlPort$DOOR_PATH"
        // A selector, by the prefixes the gateway reads as one provider's; an Anthropic ID never is one, whatever a
        // prefix looks like, since it is the person's own pick.
        val tiers = AnthropicTiers()
        val selectors = DoorSelectors { model -> tiers.tierOf(model) == null && aliases.of(model) != null }
        val tidy = DesktopSettingsTidy(DesktopSettingsFile(), records, selectors, log)
        val switch = DesktopSwitch(lineups, records, key, door, app, tidy, log)
        switch.tidy()
        this.door = door
        publish(door, lineups.lineup())
        val (lane, terminal) = claude.open(lineups, door)
        headLane.open(lineups)
        val callers = listOf(DesktopCaller(key, records, log), terminal)
        val doorway = DesktopDoor(callers, lineups)
        team.open(lineups, topology)
        network.open(doorway, NetworkCaller(lane, machine), lane, PickerRows(claude::rows), machine)
        opened = DesktopService(doorway, switch, lane, lineups, records)
    }

    /** The model catalog built again from the providers signed in now and served from here on: a provider's credential
     *  changed (a sign-in, a sign-out, a key set), or Antigravity joined. Nothing before [open]. One publication at a
     *  time, catalog and Desktop's settings together, so the last one written is always read from the newest state. */
    suspend fun republish() {
        publishing.withLock {
            val source = lineups ?: return
            source.publish()
            door?.let { publish(it, source.lineup()) }
        }
    }

    /** The model list and splice's policy given to every Claude Desktop whose applied configuration points at [door]:
     *  the person's own, and each one run beside it on a config folder of its own. */
    private suspend fun publish(door: String, lineup: DesktopLineup) {
        val models = DoorCatalog(lineup).inferenceModels(records.get().defaultModel)
        withContext(io) {
            (listOf(library) + library.beside()).forEach { desktop ->
                Cancellables.runCatchingCancellable { desktop.publish(door, models) }
                    .onFailure { log("[desktop] the configuration was not written: ${SafeFailureText.render(it)}\n") }
            }
        }
    }
}

/** Where the door is answered on the control port. */
internal const val DOOR_PATH = "/door"

/** Where the door was answered before it had two callers; it still is, for a Desktop set up then. */
internal const val DESKTOP_PATH = "/desktop"
