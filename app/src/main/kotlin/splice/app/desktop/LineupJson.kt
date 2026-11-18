// NEW: door-default (docs/specs/door-default.md, decision 1). A lineup's models and the heads left out, as
// `splice desktop --json` and `splice claude --json` both give them.
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

internal class LineupJson {
    /** Each of [models] as `{model, label, head, command}`, in order. */
    fun models(models: List<DoorModel>): JsonArray = buildJsonArray {
        models.forEach { m ->
            addJsonObject {
                put("model", m.model)
                put("label", m.label)
                put("head", m.head)
                put("command", m.command)
            }
        }
    }

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
