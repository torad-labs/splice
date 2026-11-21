// NEW: door-default (docs/specs/door-default.md, decision 1). The door's heads, and those left out of it, as
// `splice desktop --json` and `splice claude --json` give them. The models they give are the model catalog's (DoorCatalog).
package splice.app.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.CommandCredentials
import splice.core.failure.ProviderErrors

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
                status(this, lineup.providerOf(h))
            }
        }
        lineup.leftOut.forEach { h ->
            addJsonObject {
                put("head", h.head)
                put("command", h.command)
                lineup.providers[h.head]?.let { put("provider", it) }
                put("listed", false)
                put("reason", h.reason)
                lineup.providers[h.head]?.let { status(this, it) }
            }
        }
    }

    /** What [provider] says of itself now: the state of its command credential, for a provider on `auth.kind =
     *  "command"`, and its last refusal since the daemon started, its words already redacted (ProviderErrors). Each is
     *  left out where there is none. */
    private fun status(into: JsonObjectBuilder, provider: String) {
        CommandCredentials.find(provider)?.state?.fields()?.let { fields ->
            into.putJsonObject("credential") { fields.forEach { (key, value) -> put(key, value) } }
        }
        ProviderErrors.of(provider)?.let { error ->
            into.putJsonObject("last_error") {
                put("status", error.status)
                put("at", error.at.toString())
                put("message", error.message)
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
