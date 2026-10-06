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
import splice.accounts.AccountHead
import splice.accounts.pool.AccountAnswerJson
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.auth.AuthDescription
import splice.core.auth.REFUSAL_FIELD
import splice.core.head.ProviderAnswer
import splice.core.usage.QuotaFreshness

internal object ClaudeLoginRows {
    suspend fun list(
        native: List<ClaudeLoginPlaceView>,
        providers: Map<String, String>,
        carrying: Map<String, ClaudeLoginPlaceId?>,
        heads: Map<String, AccountHead>,
        nowSeconds: Long,
    ): List<JsonObject> = native.map { view ->
        val head = heads[view.head]
        json(
            view,
            providers.getValue(view.head),
            carrying[view.head],
            nowSeconds,
            head?.activePool?.view(null),
            head?.accountAuth?.descriptions()?.get("native:${view.id.wire}"),
            head?.answers?.answer("native:${view.id.wire}"),
        )
    }

    /** Same row for the roster and an explicit refresh; no credential join key is part of the payload. [carrying] is
     *  the place whose credential carried the head's newest matched request, or null before any matched. */
    fun json(
        view: ClaudeLoginPlaceView,
        provider: String,
        carrying: ClaudeLoginPlaceId?,
        nowSeconds: Long,
        pool: HeadAccountPoolView? = null,
        description: AuthDescription? = null,
        answer: ProviderAnswer? = null,
    ): JsonObject = buildJsonObject {
        put("provider", provider)
        AccountAnswerJson.write(this, answer)
        put("credential_path", view.credentialPath)
        put("credential_present", view.credentialPresent)
        put("kind", "client")
        put("label", view.id.wire)
        put("display_name", view.management?.displayName ?: view.id.command)
        put("identity_verified", view.account != null)
        put("profile_state", view.profileState.wire)
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
        selection(this, view, pool, description)
        put("carrying_request", carrying?.let { it == view.id })
        putJsonArray("heads") { add(JsonPrimitive(view.head)) }
        putJsonObject("failover_positions") { put(view.head, null as Int?) }
    }

    private fun selection(
        into: JsonObjectBuilder,
        view: ClaudeLoginPlaceView,
        pool: HeadAccountPoolView?,
        description: AuthDescription?,
    ) = with(into) {
        val key = "native:${view.id.wire}"
        val selected = pool?.accounts?.singleOrNull { it.label == key }
        val available = selected?.available == true
        put("selector_key", key)
        put("available", available)
        put("selected", selected?.selected)
        put("pinned", pool?.let { it.pinnedLabel == key })
        put("next_target", pool?.let { it.nextTargetLabel == key })
        put("auth_excluded_until_epoch_millis", selected?.authExcludedUntilEpochMillis)
        put("auth_exclusion_reason", selected?.authExclusionReason)
        val reason = description?.fields?.get(REFUSAL_FIELD) ?: view.refusal
        put("refusal", refusal(view, selected, reason))
    }

    private fun refusal(view: ClaudeLoginPlaceView, selected: HeadAccountView?, reason: String?): String? {
        val signIn = "run ${view.id.command} to sign in again."
        return when {
            selected?.available == true -> null
            reason != null -> reason
            selected?.credentialPresent != true -> "No usable native access token; $signIn"
            selected.authExclusionReason != null -> "This login was refused; $signIn"
            else -> "This login reached its subscription limit; wait for its reported reset before it can take over."
        }
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
