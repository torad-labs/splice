// NEW: desktop-door (docs/specs/desktop-door.md, decision 4). What the control plane's routes reach of Claude
// Desktop's door once the daemon has opened it: the door, its switch, and what `splice desktop` says.
package splice.app.desktop

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
