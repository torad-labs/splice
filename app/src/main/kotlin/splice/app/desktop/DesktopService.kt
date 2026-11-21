// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). What the control plane's routes reach of Claude
// Desktop's door once the daemon has opened it: the door, its switch, and what `splice desktop` says; and
// (docs/specs/door-default.md) the terminal's lane on the same door and what `splice claude` says.
package splice.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The door, its switch, the terminal's lane, and what `splice desktop` and `splice claude` say, for the routes. */
internal class DesktopService(
    val door: DesktopDoor,
    val switch: DesktopSwitch,
    val claude: ClaudeLane,
    private val providers: DesktopProviders,
    private val records: DesktopRecords,
) {
    private val lineupJson = LineupJson()
    private val catalogJson = Json { explicitNulls = false }

    /** What `splice desktop --json` says: whether Desktop is on, the door, the default model, the lineup, each head
     *  left out with the reason, each of Desktop's features in each mode, and the last turn through the door. */
    suspend fun state(): JsonObject {
        val lineup = providers.lineup()
        val record = records.get()
        return buildJsonObject {
            val catalog = DoorCatalog(lineup)
            val default = switch.defaultOf(lineup)
            val mode = switch.app.mode()
            put("on", record.on)
            val other = switch.app.other(record)
            put("mode", mode.wire)
            put("mode_said", mode.said(other))
            put("other", other)
            put("relaunched", switch.app.relaunched(record))
            put("door", switch.door)
            put("default_model", default)
            put("models", catalog.desktopJson())
            // What Desktop's picker holds, and whether it is still what the door would list now: Desktop discovers
            // models only at launch, so a sign-in or an added head reaches it at the next one.
            val picker = catalog.desktopRead(default)
            put("picker", pickerJson(picker))
            put("picker_current", record.pickerRead?.rows?.let { it == picker }?.takeIf { mode == DesktopMode.APP })
            put("left_out", lineupJson.leftOut(lineup.leftOut))
            // Each of Desktop's backend-dependent features in each mode, working or why not (df-features).
            put("features", DesktopFeatures(lineup).json())
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

    /** What `splice claude --json` says: the lane, and on the door the door, the model a session starts on and why,
     *  the picker's rows and the providers left out with the reason. On its head (decision 9): the rows it adds after
     *  Claude Code's own models and the providers left out, less `claude`'s, whose models are Claude's. */
    suspend fun claudeState(): JsonObject {
        val lane = claude.lane()
        if (lane != DOOR_LANE) {
            val lineup = providers.lineup()
            return buildJsonObject {
                put("lane", lane)
                put("models", DoorCatalog(lineup).pickerJson())
                put("left_out", lineupJson.leftOut(lineup.leftOut.filterNot { it.forwardsClientAuth }))
                providersJson(this, lineup)
            }
        }
        val lineup = providers.lineup()
        val (starting, why) = claude.starting(lineup)
        return buildJsonObject {
            put("lane", lane)
            put("door", claude.door)
            put("default_model", starting)
            put("default_from", why)
            put("models", DoorCatalog(lineup).pickerJson())
            put("left_out", lineupJson.leftOut(lineup.leftOut))
            providersJson(this, lineup)
            claude.login.theirs()?.let { put("own_login", it.toString()) }
        }
    }

    /** The model catalog published last, as `model-catalog.json` holds it, for `/api/models`. */
    fun catalog(): JsonObject =
        catalogJson.encodeToJsonElement(ProviderCatalog.serializer(), providers.catalog()).jsonObject

    /** [lineup]'s providers as `providers`, and again as `heads`, the name a script read them by when heads ran
     *  them. */
    private fun providersJson(into: JsonObjectBuilder, lineup: DesktopLineup) {
        val listed = lineupJson.providers(lineup)
        into.put("providers", listed)
        into.put("heads", listed)
    }

    /** Desktop's picker rows, each `{id, label}`, in order. */
    private fun pickerJson(rows: List<DesktopPickerRow>): JsonArray = buildJsonArray {
        rows.forEach { row ->
            addJsonObject {
                put("id", row.id)
                put("label", row.label)
            }
        }
    }
}
