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
import splice.accounts.pool.AccountNameFacts
import splice.accounts.pool.AccountNaming
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.auth.AuthDescription
import splice.core.auth.REFUSAL_FIELD
import splice.core.usage.QuotaFreshness
import splice.core.usage.QuotaJson
import splice.core.usage.QuotaReadFailureSource

/** [naming] is how each row is called (labels only): the names a person chose, by ref, read first. [readFailures] says
 *  why a login's last limits read failed, beside "Not read". */
internal class ClaudeLoginRows(
    private val naming: AccountNaming = AccountNaming(),
    private val readFailures: QuotaReadFailureSource? = null,
) {
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
            head,
        )
    }

    /** One roster row; no credential join key is part of the payload. [carrying] is
     *  the place whose credential carried the head's newest matched request, or null before any matched. */
    suspend fun json(
        view: ClaudeLoginPlaceView,
        provider: String,
        carrying: ClaudeLoginPlaceId?,
        nowSeconds: Long,
        head: AccountHead? = null,
    ): JsonObject = buildJsonObject {
        val place = "native:${view.id.wire}"
        val answer = head?.answers?.answer(place)
        put("provider", provider)
        AccountAnswerJson.write(this, answer)
        put("credential_present", view.credential.present)
        put("kind", "client")
        put("label", view.id.wire)
        // Labels only: a saved name a person gave, else `<Plan> · <ref>`; the command word is no account's name.
        val given = view.management?.takeIf { it.canRename }?.displayName
        val ref = view.identity.account?.ref
        val name = naming.name(AccountNameFacts(given, null, ref, view.quota?.plan))
        // A place no credential has proved yet reads "Account", never the command's word, which names no account
        // (Marlin's Accounts walk, ruled via splice-lead, Oct 12); the page says it is Not read.
        name.write(this)
        put("identity_verified", view.identity.account != null)
        put("can_remove", view.management?.canRemove == true)
        // a proved login can always be named: its name lives in splice's own state, never in his credential files
        put("can_rename", view.management?.canRename == true || ref != null)
        putJsonObject("edit_target") {
            put("kind", "native")
            put("id", view.id.wire)
        }
        put("primary", false)
        put("single_login", true)
        putJsonObject("login_place") { put("id", view.id.wire) }
        put("plan", view.quota?.plan)
        windows(this, view, nowSeconds)
        put("last_read_failure", readFailures?.failure(view.head, place)?.json() ?: JsonNull)
        selection(this, view, head?.activePool?.view(null), head?.accountAuth?.descriptions()?.get(place))
        put("carrying_request", carrying?.let { it == view.id })
        putJsonArray("heads") { add(JsonPrimitive(view.head)) }
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
        // The console reads WHICH head pins a row, because a row shared by two heads can be pinned on one. A native login
        // belongs to one head, so the answer is that head when the pin names this login.
        putJsonArray("pinned_heads") { if (pool?.pinnedLabel == key) add(JsonPrimitive(view.head)) }
        put("auth_excluded_until_epoch_millis", selected?.credential?.excludedUntilEpochMillis)
        put("auth_exclusion_reason", selected?.credential?.exclusionReason)
        val reason = description?.fields?.get(REFUSAL_FIELD) ?: view.credential.refusal
        put("refusal", refusal(view, selected, reason))
        put("token_renews", !available && description?.fields?.get(TOKEN_RENEWS_FIELD) == "true")
    }

    private fun refusal(view: ClaudeLoginPlaceView, selected: HeadAccountView?, reason: String?): String? {
        val signIn = "run ${view.id.command} to sign in again."
        return when {
            selected?.available == true -> null
            reason != null -> reason
            selected?.credential?.present != true -> "No usable native access token; $signIn"
            selected.credential.exclusionReason != null -> "This login was refused; $signIn"
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
        put("five_hour_current", five != null && QuotaFreshness.current(observed, five.resetsAt, nowSeconds))
        put("seven_day_used_percent", seven?.usedPercent)
        put("seven_day_reset_epoch_seconds", seven?.resetsAt)
        put("seven_day_window_seconds", seven?.windowSeconds)
        put("seven_day_current", seven != null && QuotaFreshness.current(observed, seven.resetsAt, nowSeconds))
        QuotaJson().putModels(into, "seven_day_models", quota?.modelsRunningAt(nowSeconds).orEmpty())
        put("observed_at_epoch_seconds", observed)
    }
}
