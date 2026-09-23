// PORT-OF: daemon/control/.../api/auth/AuthRoutes.kt (AccountPoolJson) — the account pool's one JSON
// projection, written into /api/auth and /api/usage alike; the masked auth fields it may carry are an
// allowlist, never a denylist.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.auth.AuthDescription

public class AccountPoolJson {
    public fun write(
        into: JsonObjectBuilder,
        view: HeadAccountPoolView,
        descriptions: Map<String, AuthDescription> = emptyMap(),
    ) {
        into.putJsonObject("account_pool") {
            put("selected_label", view.selectedLabel)
            putJsonArray("accounts") {
                view.accounts.forEach { account ->
                    addJsonObject {
                        put("label", account.label)
                        put("primary", account.primary)
                        put("selected", account.selected)
                        put("available", account.available)
                        put("credential_present", account.credentialPresent)
                        put("auth_excluded_until_epoch_millis", account.authExcludedUntilEpochMillis)
                        put("auth_exclusion_reason", account.authExclusionReason)
                        put("plan", account.plan)
                        put("five_hour_used_percent", account.fiveHourUsedPercent)
                        put("five_hour_reset_epoch_seconds", account.fiveHourResetEpochSeconds)
                        put("seven_day_used_percent", account.sevenDayUsedPercent)
                        put("seven_day_reset_epoch_seconds", account.sevenDayResetEpochSeconds)
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
