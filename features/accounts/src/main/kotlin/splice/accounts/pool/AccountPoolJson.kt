// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (AccountPoolJson) — the account pool's one JSON
// projection, written into /api/auth and /api/usage alike; the masked auth fields it may carry are an
// allowlist, never a denylist. V4-220 item 6b: each account's `auth` carries the verdict shape too.
package splice.accounts.pool

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.accounts.CredentialVerdictJson
import splice.core.auth.AuthDescription
import splice.core.head.ProviderAnswer

/** A newest answer, not refusal history. Later success, overload or server error therefore renders null. */
internal object AccountAnswerJson {
    fun write(into: JsonObjectBuilder, answer: ProviderAnswer?) {
        val refusal = answer?.takeIf { it.refused }?.let {
            buildJsonObject {
                put("status", it.status)
                put("at_ms", it.observedAtEpochMs)
            }
        }
        into.put("last_refusal", refusal ?: JsonNull)
    }
}

public class AccountPoolJson {
    public fun write(
        into: JsonObjectBuilder,
        view: HeadAccountPoolView,
        descriptions: Map<String, AuthDescription> = emptyMap(),
    ) {
        into.putJsonObject("account_pool") {
            put("selected_label", view.selectedLabel)
            put("pinned_label", view.pinnedLabel)
            putJsonArray("accounts") {
                view.accounts.forEach { account ->
                    addJsonObject {
                        put("label", account.label)
                        put("primary", account.primary)
                        put("selected", account.selected)
                        put("available", account.available)
                        put("blocked_until_epoch_seconds", view.blockedUntilEpochSecondsByLabel[account.label])
                        put("credential_present", account.credential.present)
                        put("auth_excluded_until_epoch_millis", account.credential.excludedUntilEpochMillis)
                        put("auth_exclusion_reason", account.credential.exclusionReason)
                        put("plan", account.plan)
                        put("five_hour_used_percent", account.quota.fiveHour.usedPercent)
                        put("five_hour_reset_epoch_seconds", account.quota.fiveHour.resetEpochSeconds)
                        put("seven_day_used_percent", account.quota.sevenDay.usedPercent)
                        put("seven_day_reset_epoch_seconds", account.quota.sevenDay.resetEpochSeconds)
                        descriptions[account.label]?.let { description -> auth(this, description) }
                    }
                }
            }
            view.lastSwitch?.let { switch ->
                putJsonObject("last_switch") {
                    put("from", switch.from)
                    put("to", switch.to)
                    put("reason", switch.reason)
                    put("at_epoch_millis", switch.atEpochMillis)
                }
            }
        }
    }

    private fun auth(into: JsonObjectBuilder, description: AuthDescription) {
        into.putJsonObject("auth") {
            put("kind", description.kind)
            put("present", description.present)
            CredentialVerdictJson().write(this, description.verdict)
            description.fields.forEach { (key, value) ->
                if (safeAuthField(key)) put(key, value)
            }
        }
    }

    private fun safeAuthField(key: String): Boolean = when (key) {
        "account_id_masked", "login", "refresh_latched" -> true
        else -> false
    }
}
