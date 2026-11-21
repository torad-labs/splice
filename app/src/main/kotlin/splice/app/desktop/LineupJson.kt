// NEW: door-default (docs/specs/door-default.md, decision 1). The door's heads, and those left out of it, as
// `splice desktop --json` and `splice claude --json` give them. The models they give are the model catalog's (DoorCatalog).
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

internal class LineupJson {
    /** Every head of [lineup], in the door or left out of it, as `{head, command, provider, listed}` and, for one left
     *  out, `reason`: what `splice claude` and `splice doctor` say of each head (custom-provider). */
    fun heads(lineup: DesktopLineup): JsonArray = buildJsonArray {
        lineup.heads.forEach { h ->
            addJsonObject {
                put("head", h.key)
                put("command", h.relay.command)
                put("provider", lineup.providerOf(h))
                put("listed", true)
            }
        }
        lineup.leftOut.forEach { h ->
            addJsonObject {
                put("head", h.head)
                put("command", h.command)
                lineup.providers[h.head]?.let { put("provider", it) }
                put("listed", false)
                put("reason", h.reason)
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
