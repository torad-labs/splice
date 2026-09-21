// NEW: v0.4.0 FEATURES.md §11 — the account pools as the CLI sees them out of process: the daemon's
// /api/auth account_pool projection (labels, availability, plan windows, the last switch), read with
// the mgmt key and parsed into the control plane's own view type. The projection carries no
// credential, raw account id, e-mail or path, and this reader keeps only the fields it names.
package splice.app.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.control.HeadAccountPoolView
import splice.control.HeadAccountSwitchView
import splice.control.HeadAccountView
import splice.core.util.Cancellables
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.upstream.credentials.AccountLabelPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val READ_TIMEOUT_S = 5L

/** Pools keyed by head (empty when no head holds one); NULL when the projection could not be read —
 *  no mgmt key, nothing answering, or a body that is not the /api/auth shape. */
private const val HTTP_OK = 200

internal fun interface AccountPoolRead {
    operator fun invoke(port: Int, env: EnvReader): Map<String, HeadAccountPoolView>?
}

internal class JdkAccountPoolRead(
    private val projection: AccountPoolProjection = AccountPoolProjection(),
) : AccountPoolRead {
    private val client = HttpClient.newHttpClient()

    override fun invoke(port: Int, env: EnvReader): Map<String, HeadAccountPoolView>? {
        val key = (AdminSupport.readMgmtKey(env) as? MgmtKeyRead.Present)?.key ?: return null
        // ast-grep-ignore: kt-no-silent-result-collapse -- 2026-09-17 (V4-112): the file header declares the null: no mgmt key, nothing answering, or a body that is not the /api/auth shape all read as 'no pools to show'.
        return Cancellables.runCatchingCancellable {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/auth"))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_S))
                .header("Authorization", "Bearer $key")
                .GET()
                .build()
            val reply = client.send(request, HttpResponse.BodyHandlers.ofString())
            // A 401 (a stale mgmt key) is a read that FAILED, not a daemon with no pools.
            reply.takeIf { it.statusCode() == HTTP_OK }?.let { projection.parse(it.body()) }
        }.getOrNull()
    }
}

/** The account_pool object under each head of /api/auth, and nothing else from that payload. */
internal class AccountPoolProjection {
    private val json = Json { ignoreUnknownKeys = true }

    // SignInPlanner's portable wrapper shape: these keys become text and bare login arguments.
    private val headKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val authExclusionReasons = setOf(
        "terminal_401",
        "credential_missing",
        "recovery_probe_in_flight",
    )

    fun parse(body: String): Map<String, HeadAccountPoolView> =
        json.parseToJsonElement(body).jsonObject.mapNotNull { (head, value) ->
            if (!headKey.matches(head)) return@mapNotNull null
            ((value as? JsonObject)?.get("account_pool") as? JsonObject)?.let { pool -> head to view(pool) }
        }.toMap()

    private fun view(pool: JsonObject): HeadAccountPoolView {
        val rawAccounts = (pool["accounts"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val accounts = rawAccounts.mapNotNull(::account)
        val selectedLabel = label(pool, "selected_label")
        val selectionSupplied = pool["selected_label"]?.let { it != JsonNull } == true
        val missingSelection = selectionSupplied && accounts.none { it.label == selectedLabel }
        val droppedSelected = rawAccounts.any {
            JsonScalars.str(it, "selected") == "true" && label(it, "label") == null
        }
        return HeadAccountPoolView(
            selectedLabel = selectedLabel,
            accounts = accounts,
            lastSwitch = (pool["last_switch"] as? JsonObject)?.let(::switch),
            selectionUnknown = missingSelection || droppedSelected,
        )
    }

    private fun account(a: JsonObject): HeadAccountView? {
        val label = label(a, "label") ?: return null
        val authExclusion = authExclusion(a)
        return HeadAccountView(
            label = label,
            primary = JsonScalars.str(a, "primary") == "true",
            selected = JsonScalars.str(a, "selected") == "true",
            available = JsonScalars.str(a, "available") == "true",
            plan = JsonScalars.str(a, "plan"),
            fiveHourUsedPercent = JsonScalars.str(a, "five_hour_used_percent")?.toDoubleOrNull(),
            fiveHourResetEpochSeconds = JsonScalars.long(a, "five_hour_reset_epoch_seconds"),
            sevenDayUsedPercent = JsonScalars.str(a, "seven_day_used_percent")?.toDoubleOrNull(),
            sevenDayResetEpochSeconds = JsonScalars.long(a, "seven_day_reset_epoch_seconds"),
            credentialPresent = credentialPresent(a),
            authExcludedUntilEpochMillis = authExclusion.first,
            authExclusionReason = authExclusion.second,
        )
    }

    private fun authExclusion(a: JsonObject): Pair<Long?, String?> {
        val reason = JsonScalars.str(a, "auth_exclusion_reason")
            ?.takeIf(authExclusionReasons::contains)
            ?: return null to null
        return JsonScalars.long(a, "auth_excluded_until_epoch_millis") to reason
    }

    /** Older daemons exposed presence only in masked auth; absent on both means legacy-present. */
    private fun credentialPresent(a: JsonObject): Boolean =
        JsonScalars.str(a, "credential_present")?.toBooleanStrictOrNull()
            ?: (a["auth"] as? JsonObject)?.let { JsonScalars.str(it, "present")?.toBooleanStrictOrNull() }
            ?: true

    private fun switch(s: JsonObject): HeadAccountSwitchView? {
        val from = label(s, "from") ?: return null
        val to = label(s, "to") ?: return null
        val reason = (s["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf(AccountSwitchReasonText::isSafe)
        val at = JsonScalars.long(s, "at_epoch_millis") ?: 0L
        return reason?.let { HeadAccountSwitchView(from, to, it, at) }
    }

    /** A stale or foreign daemon is still a boundary: rejected labels never enter a printable view. */
    private fun label(obj: JsonObject, key: String): String? = (obj[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content?.takeIf(AccountLabelPolicy::isSafe)
}

// NEW: v0.4.0 FEATURES.md §11 — AccountPool.switchReason's vocabulary at both CLI text boundaries.
// The existing projection wording remains accepted; foreign prose and terminal controls do not.
internal object AccountSwitchReasonText {
    private val reasons = setOf(
        "primary account reset",
        "rate limit exceeds turn wait budget",
        "5-hour quota exhausted",
        "7-day quota exhausted",
        "account unavailable",
        "7d window exhausted",
    )

    fun isSafe(reason: String): Boolean = reason in reasons
}
