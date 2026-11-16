// NEW: desktop-door (docs/specs/desktop-door.md). The daemon's part of Claude Desktop's door: the door itself, the
// switch `splice desktop` drives, and what `splice desktop` says. Built with the daemon and opened once its heads are
// up, on the control port, so the door's address is the same at every start. At each start, while Desktop is on, the
// picker in the settings file is made to follow the heads splice now serves.
package splice.app.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.app.control.ManagedHead
import splice.app.share.ManagedSharedHeads
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.topology.Topology
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.launch.recipe.LaunchDesktop
import splice.share.RefusalVoice
import splice.share.RelaySpeaker
import splice.share.ShareMachine
import splice.share.ShareRelay
import splice.upstream.Ticker
import splice.upstream.codemode.ProcessDispatchers
import splice.upstream.codemode.ProcessTicker

internal class DaemonDesktop(
    private val topology: Topology,
    private val config: ConfigService,
    private val turnKey: TurnKey,
    private val statePaths: StatePaths,
    private val log: LogSink,
) {
    private val key = DesktopKey(statePaths.desktopKeyFile, log)
    private val records = DesktopRecords(statePaths.desktopFile, log)

    /** The door as a launch of the `claude` command sees it, for the launch service. */
    val launching: LaunchDesktop = DesktopLaunching(records, topology)

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
        val io = ProcessDispatchers().io()
        val relay = ShareRelay(turnKey, controlPort, machine, io, log, RelaySpeaker(voice, DESKTOP_TAG))
        val lineups = DesktopHeads(running)
        val door = "http://127.0.0.1:$controlPort$DESKTOP_PATH"
        val switch = DesktopSwitch(lineups, records, key, DesktopSettingsFile(), door, log)
        opened = DesktopService(DesktopDoor(relay, lineups, records, key, log), switch, lineups, records)
    }

    /** While Desktop is on, the picker in the file follows splice: now, and every [FOLLOW_MS] in [scope], so a head
     *  signed in or out shows in the next session's picker with no command run. A pass that fails is logged and the
     *  next one runs. */
    fun watch(scope: CoroutineScope, ticker: Ticker = ProcessTicker()) {
        scope.launch {
            do {
                Cancellables.runCatchingCancellable { opened?.follow() }.onFailure {
                    log("[desktop] the picker did not follow splice (${SafeFailureText.render(it)})\n")
                }
            } while (ticker.awaitTick(FOLLOW_MS))
        }
    }
}

/** Whether Desktop is on and what `on` replaced, from the door's record; a selector, by the discovery prefixes of the
 *  heads in splice.toml. */
private class DesktopLaunching(private val records: DesktopRecords, private val topology: Topology) : LaunchDesktop {
    override fun replaced(): JsonObject? = records.get().takeIf { it.on }?.let { it.replaced ?: JsonObject(emptyMap()) }

    override fun isSelector(model: String): Boolean =
        topology.heads.values.any { it.discoveryPrefix.isNotEmpty() && model.startsWith(it.discoveryPrefix) }
}

/** The door, its switch, and what `splice desktop` says, for the control plane's routes. */
internal class DesktopService(
    val door: DesktopDoor,
    val switch: DesktopSwitch,
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
) {
    /** While Desktop is on, the file's picker made to list the heads splice serves now. */
    suspend fun follow() {
        switch.follow(heads.lineup())
    }

    /** What `splice desktop --json` says: whether Desktop is on, the door, the default model, the lineup, each head
     *  left out with the reason, and the last turn through the door. */
    suspend fun state(): JsonObject {
        val lineup = heads.lineup()
        switch.follow(lineup)
        val record = records.get()
        return buildJsonObject {
            put("on", record.on)
            put("door", switch.door)
            put("default_model", switch.defaultOf(lineup).first.ifEmpty { null })
            putJsonArray("models") {
                lineup.models.forEach { m ->
                    addJsonObject {
                        put("model", m.model)
                        put("label", m.label)
                        put("head", m.head)
                        put("command", m.command)
                    }
                }
            }
            putJsonArray("left_out") {
                lineup.leftOut.forEach { h ->
                    addJsonObject {
                        put("head", h.head)
                        put("command", h.command)
                        put("reason", h.reason)
                    }
                }
            }
            val last = record.lastTurn
            if (last == null) {
                put("last_turn", JsonNull)
            } else {
                putJsonObject("last_turn") {
                    put("at", last.at)
                    put("session_id", last.sessionId)
                    put("head", last.head)
                    put("model_asked", last.modelAsked)
                    put("model", last.model)
                }
            }
        }
    }
}

/** Where the door is answered on the control port. */
internal const val DESKTOP_PATH = "/desktop"

// why: a login signed in or out reaches the next session's picker within this; each pass reads each head's login and
// writes the file only when its picker changed.
private const val FOLLOW_MS = 15_000L

// The tag the relay's log lines carry for a door turn.
private const val DESKTOP_TAG = "desktop"
