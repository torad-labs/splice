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

import kotlinx.coroutines.withContext
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

    /** The door on [controlPort], serving [providers], with the model catalog of the providers signed in now published
     *  before any route serves it, and again whenever [rosters] ask a provider's list with a new credential. */
    suspend fun open(providers: ServingProviders, controlPort: Int, rosters: ModelRosters) {
        val machine = ShareMachine(log).name()
        val catalog = PublishedCatalog(CatalogBuild(), CatalogFile(statePaths.modelCatalogFile, log), io)
        val lineups = DesktopProviders(providers, aliases, catalog)
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
        publishModels(door, lineups.lineup())
        val (lane, terminal) = claude.open(lineups, door)
        headLane.open(lineups)
        val callers = listOf(DesktopCaller(key, records, log), terminal)
        val doorway = DesktopDoor(callers, lineups)
        team.open(lineups, topology)
        network.open(doorway, NetworkCaller(lane, machine), lane, PickerRows(claude::rows), machine)
        opened = DesktopService(doorway, switch, lane, lineups, records)
    }

    /** The model catalog built again from the providers signed in now and served from here on: a provider's credential
     *  changed (a sign-in, a sign-out, a key set). Nothing before [open]. */
    suspend fun republish() {
        val source = lineups ?: return
        source.publish()
        door?.let { publishModels(it, source.lineup()) }
    }

    private suspend fun publishModels(door: String, lineup: DesktopLineup) {
        val models = DoorCatalog(lineup).inferenceModels(records.get().defaultModel)
        withContext(io) {
            (listOf(library) + library.beside()).forEach { desktop ->
                Cancellables.runCatchingCancellable { desktop.publishModels(door, models) }
                    .onFailure { log("[desktop] the model list was not written: ${SafeFailureText.render(it)}\n") }
            }
        }
    }
}

/** Where the door is answered on the control port. */
internal const val DOOR_PATH = "/door"

/** Where the door was answered before it had two callers; it still is, for a Desktop set up then. */
internal const val DESKTOP_PATH = "/desktop"
