// NEW: native command rows carry their own identity and measured windows, without pool or money attribution.
package splice.accounts.claude

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.core.usage.QuotaFreshness

internal object ClaudeLoginRows {
    /** Same row for the roster and an explicit refresh; no credential join key is part of the payload. [carrying] is
     *  the place whose credential carried the head's newest matched request, or null before any matched. */
    fun json(
        view: ClaudeLoginPlaceView,
        provider: String,
        carrying: ClaudeLoginPlaceId?,
        nowSeconds: Long,
    ): JsonObject = buildJsonObject {
        put("provider", provider)
        put("credential_path", view.credentialPath)
        put("credential_present", view.credentialPresent)
        put("kind", "client")
        put("label", view.id.wire)
        put("display_name", view.management?.displayName ?: view.id.command)
        put("identity_verified", view.account != null)
        put("can_remove", view.management?.canRemove == true)
        put("can_rename", view.management?.canRename == true)
        putJsonObject("edit_target") {
            put("kind", "native")
            put("id", view.id.wire)
        }
        put("primary", false)
        put("single_login", true)
        putJsonObject("login_place") {
            put("id", view.id.wire)
            put("command", view.id.command)
        }
        val account = view.account?.let {
            buildJsonObject {
                put("uuid", it.uuid)
                put("email", it.email)
            }
        }
        put("account", account ?: JsonNull)
        put("plan", view.quota?.plan)
        windows(this, view, nowSeconds)
        put("held", view.standing.held)
        put("held_until_epoch_seconds", view.standing.untilEpochSeconds)
        put("available", null as Boolean?)
        put("selected", null as Boolean?)
        put("carrying_request", carrying?.let { it == view.id })
        put("pinned", null as Boolean?)
        put("next_target", null as Boolean?)
        put("auth_excluded_until_epoch_millis", null as Long?)
        put("auth_exclusion_reason", null as String?)
        put("refusal", view.refusal)
        putJsonArray("heads") { add(JsonPrimitive(view.head)) }
        putJsonObject("failover_positions") { put(view.head, null as Int?) }
    }

    private fun windows(into: JsonObjectBuilder, view: ClaudeLoginPlaceView, nowSeconds: Long) = with(into) {
        val quota = view.quota
        val observed = quota?.observedAtEpochSeconds
        val five = quota?.fiveHour
        val seven = quota?.sevenDay
        put("five_hour_used_percent", five?.usedPercent)
        put("five_hour_reset_epoch_seconds", five?.resetsAt)
        put("five_hour_window_seconds", five?.windowSeconds)
        put("five_hour_limit_percent", five?.let { 100 })
        put("five_hour_current", five != null && QuotaFreshness.current(observed, five.resetsAt, nowSeconds))
        put("seven_day_used_percent", seven?.usedPercent)
        put("seven_day_reset_epoch_seconds", seven?.resetsAt)
        put("seven_day_window_seconds", seven?.windowSeconds)
        put("seven_day_limit_percent", seven?.let { 100 })
        put("seven_day_current", seven != null && QuotaFreshness.current(observed, seven.resetsAt, nowSeconds))
        put("observed_at_epoch_seconds", observed)
    }
}
