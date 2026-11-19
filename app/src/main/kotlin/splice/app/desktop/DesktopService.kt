// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). What the control plane's routes reach of Claude
// Desktop's door once the daemon has opened it: the door, its switch, and what `splice desktop` says; and
// (docs/specs/door-default.md) the terminal's lane on the same door and what `splice claude` says. The door's address
// and the model a session starts on are the lane's for both.
package splice.app.desktop

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.topology.CLAUDE_COMMAND

/** The door, its switch, the terminal's lane, and what `splice desktop` and `splice claude` say, for the routes. */
internal class DesktopService(
    val door: DesktopDoor,
    val switch: DesktopSwitch,
    val claude: ClaudeLane,
    private val heads: DesktopHeads,
    private val records: DesktopRecords,
) {
    private val lineupJson = LineupJson()

    /** What `splice desktop --json` says: whether Desktop is on, the door, the model a session starts on (the one
     *  `claude` starts on), the lineup, each head left out with the reason, and the last turn through the door. */
    suspend fun state(): JsonObject {
        val lineup = heads.lineup()
        val record = records.get()
        return buildJsonObject {
            put("on", record.on)
            put("door", claude.door)
            put("default_model", claude.starting(lineup).first)
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
     *  the picker's rows and the heads left out with the reason. On its head (decision 9): the rows it adds after
     *  Claude Code's own models and the heads left out, less the one `claude` runs on, whose models are Claude's. */
    suspend fun claudeState(): JsonObject {
        val lane = claude.lane()
        if (lane != DOOR_LANE) {
            val lineup = heads.lineup()
            return buildJsonObject {
                put("lane", lane)
                put("models", lineupJson.models(lineup.picker))
                put("left_out", lineupJson.leftOut(lineup.leftOut.filterNot { it.command == CLAUDE_COMMAND }))
            }
        }
        val lineup = heads.lineup()
        val (starting, why) = claude.starting(lineup)
        return buildJsonObject {
            put("lane", lane)
            put("door", claude.door)
            put("default_model", starting)
            put("default_from", why)
            put("models", lineupJson.models(lineup.picker))
            put("left_out", lineupJson.leftOut(lineup.leftOut))
            claude.login.theirs()?.let { put("own_login", it.toString()) }
        }
    }
}
