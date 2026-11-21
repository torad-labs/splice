// NEW: door-default (docs/specs/door-default.md, decision 1). The heads left out of the door, as `splice desktop --json`
// and `splice claude --json` both give them. The models they give are the model catalog's (DoorCatalog).
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

internal class LineupJson {
    /** Each of [leftOut] as `{head, command, reason}`, in order. */
    fun leftOut(leftOut: List<LeftOut>): JsonArray = buildJsonArray {
        leftOut.forEach { h ->
            addJsonObject {
                put("head", h.head)
                put("command", h.command)
                put("reason", h.reason)
            }
        }
    }
}
