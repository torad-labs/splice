// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). What the control plane's routes reach of Claude
// Desktop's door once the daemon has opened it: the door, its switch, and what `splice desktop` says; and
// (docs/specs/door-default.md) the terminal's lane on the same door and what `splice claude` says.
package splice.app.desktop

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The door, its switch, the terminal's lane, and what `splice desktop` and `splice claude` say, for the routes. */
internal class DesktopService(
    val door: DesktopDoor,
    val switch: DesktopSwitch,
    val claude: ClaudeLane,
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
) {
    private val lineupJson = LineupJson()

    /** What `splice desktop --json` says: whether Desktop is on, the door, the default model, the lineup, each head
     *  left out with the reason, and the last turn through the door. */
    suspend fun state(): JsonObject {
        val lineup = heads.lineup()
        val record = records.get()
        return buildJsonObject {
            put("on", record.on)
            put("door", switch.door)
            put("default_model", switch.defaultOf(lineup))
            put("models", lineupJson.models(lineup.models))
            put("left_out", lineupJson.leftOut(lineup.leftOut))
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
     *  the picker's rows and the heads left out with the reason. */
    suspend fun claudeState(): JsonObject {
        val lane = claude.lane()
        if (lane != DOOR_LANE) return buildJsonObject { put("lane", lane) }
        val lineup = heads.lineup()
        val (starting, why) = claude.starting(lineup)
        return buildJsonObject {
            put("lane", lane)
            put("door", claude.door)
            put("default_model", starting)
            put("default_from", why)
            put("models", lineupJson.models(lineup.signedIn))
            put("left_out", lineupJson.leftOut(lineup.leftOut))
            claude.login.theirs()?.let { put("own_login", it.toString()) }
        }
    }
}
