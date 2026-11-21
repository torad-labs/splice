// NEW: door-default (docs/specs/door-default.md, decision 1); gateway-no-heads. The door's providers, and those left out
// of it, as `splice desktop --json` and `splice claude --json` give them. The models they give are the model catalog's
// (DoorCatalog). `head` and `command` are what a head was named and run as; a provider has neither, so both carry its
// key, for a script that reads them.
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
    /** Every provider of [lineup], in the door or left out of it, as `{provider, listed}` and, for one left out,
     *  `reason`: what `splice claude` and `splice doctor` say of each provider (custom-provider). */
    fun providers(lineup: DesktopLineup): JsonArray = buildJsonArray {
        lineup.providers.forEach { provider ->
            addJsonObject {
                named(this, provider.key)
                put("listed", true)
                status(this, provider.key)
            }
        }
        lineup.leftOut.forEach { out ->
            addJsonObject {
                named(this, out.provider)
                put("listed", false)
                put("reason", out.reason)
                status(this, out.provider)
            }
        }
    }

    /** Each of [leftOut] as `{provider, reason}`, in order. */
    fun leftOut(leftOut: List<LeftOut>): JsonArray = buildJsonArray {
        leftOut.forEach { out ->
            addJsonObject {
                named(this, out.provider)
                put("reason", out.reason)
            }
        }
    }

    private fun named(into: JsonObjectBuilder, provider: String) {
        into.put("provider", provider)
        into.put("head", provider)
        into.put("command", provider)
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
}
