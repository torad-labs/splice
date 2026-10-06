// NEW: doctor and status project Accounts' authoritative roster, without retaining its private fields.
package splice.diagnostics.doctor.accounts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountView
import splice.core.head.ProviderAnswer
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.diagnostics.doctor.AccountPoolProjection
import splice.diagnostics.doctor.AccountPoolText
import splice.diagnostics.doctor.AccountPoolsRead
import splice.diagnostics.doctor.CheckStatus
import splice.diagnostics.doctor.DoctorCheck
import splice.diagnostics.doctor.FIX_RESTART
import splice.diagnostics.doctor.NativeLoginHealth
import splice.upstream.credentials.AccountLabelPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// why: one roster key is shared by account identity and refusal ownership.
private const val ACCOUNT_LABEL_FIELD = "label"

internal class AccountRosterProjection {
    private val pools = AccountPoolProjection()
    private val headKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    fun read(body: String): AccountPoolsRead = Cancellables.runCatchingCancellable { parse(body) }
        .getOrElse {
            AccountPoolsRead.Unread(
                "the daemon's /api/accounts answered, but not with the account projection this build reads",
                FIX_RESTART,
            )
        }

    private fun parse(body: String): AccountPoolsRead.Read {
        val root = Json.parseToJsonElement(body).jsonObject
        val rows = requireNotNull(root["accounts"] as? JsonArray).filterIsInstance<JsonObject>()
        val byHead = rows.flatMap { row ->
            (row["heads"] as? JsonArray).orEmpty().mapNotNull { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(headKey::matches)
                    ?.let { it to row }
            }
        }.groupBy({ it.first }, { it.second })
        val metadata = (root["head_pools"] as? JsonObject)?.let { pools.parse(it.toString()) }.orEmpty()
        val views = byHead.mapValues { (head, roster) -> view(head, roster, metadata[head]) }
        val native = byHead.flatMap { (head, roster) -> roster.mapNotNull { native(head, it) } }
        val refusals = byHead.mapNotNull { (head, roster) ->
            roster.filter { unkeyedRefusal(head, it) }.mapNotNull(::refusal)
                .maxByOrNull { it.observedAtEpochMs }?.let { head to it }
        }.toMap()
        val keyed = byHead.mapValues { (head, roster) ->
            roster.mapNotNull { row ->
                val label = refusalLabel(head, row) ?: return@mapNotNull null
                refusal(row)?.let { label to it }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, answers) ->
                answers.maxBy { it.observedAtEpochMs }
            }
        }.filterValues { it.isNotEmpty() }
        return AccountPoolsRead.Read(views, native, refusals).also { it.accountRefusals = keyed }
    }

    private fun view(head: String, rows: List<JsonObject>, metadata: HeadAccountPoolView?): HeadAccountPoolView {
        val accounts = rows.mapNotNull { account(head, it, metadata) }.distinctBy { it.label }
        val carrying = rows.filter { JsonScalars.str(it, "carrying_request") == "true" }
        val selected = carrying.singleOrNull()?.let { selector(head, it) }
            ?: metadata?.selectedLabel ?: accounts.firstOrNull { it.selected }?.label
        return HeadAccountPoolView(
            selected,
            accounts.map { it.copy(selected = it.label == selected) },
            metadata?.lastSwitch,
            selectionUnknown = (carrying.isEmpty() && metadata?.selectionUnknown == true) ||
                unsafeSelection(head, rows) ||
                (selected != null && accounts.none { it.label == selected }),
            pinnedLabel = metadata?.pinnedLabel,
            nextTargetLabel = metadata?.nextTargetLabel,
            blockedUntilEpochSecondsByLabel = metadata?.blockedUntilEpochSecondsByLabel.orEmpty(),
        )
    }

    private fun unsafeSelection(head: String, rows: List<JsonObject>): Boolean =
        rows.count { JsonScalars.str(it, "carrying_request") == "true" } > 1 || rows.any {
            selector(head, it) == null &&
                (JsonScalars.str(it, "selected") == "true" || JsonScalars.str(it, "carrying_request") == "true")
        }

    private fun account(head: String, row: JsonObject, metadata: HeadAccountPoolView?): HeadAccountView? {
        val label = selector(head, row) ?: return null
        val single = JsonScalars.str(row, "single_login") == "true" && row["login_place"] !is JsonObject
        val available = JsonScalars.str(row, "available")?.toBooleanStrictOrNull()
            ?: (single && JsonScalars.str(row, "credential_present") == "true")
        val fields = row + mapOf(
            ACCOUNT_LABEL_FIELD to JsonPrimitive(label),
            "available" to JsonPrimitive(available),
            "selected" to JsonPrimitive(single || JsonScalars.str(row, "selected") == "true"),
        )
        return pools.account(JsonObject(fields))?.let { account ->
            val pooled = metadata?.accounts?.singleOrNull { it.label == account.label } ?: account
            if (row["login_place"] is JsonObject) {
                // Native presence and windows belong to Accounts' command-local reading, not the pool's usable-token cache.
                pooled.copy(
                    credentialPresent = account.credentialPresent,
                    plan = account.plan,
                    fiveHourUsedPercent = account.fiveHourUsedPercent.takeUnless {
                        JsonScalars.str(row, "five_hour_current") == "false"
                    },
                    fiveHourResetEpochSeconds = account.fiveHourResetEpochSeconds,
                    sevenDayUsedPercent = account.sevenDayUsedPercent.takeUnless {
                        JsonScalars.str(row, "seven_day_current") == "false"
                    },
                    sevenDayResetEpochSeconds = account.sevenDayResetEpochSeconds,
                    quotaObservedAtEpochSeconds = account.quotaObservedAtEpochSeconds,
                )
            } else {
                pooled.copy(credentialPresent = account.credentialPresent)
            }
        }
    }

    private fun selector(head: String, row: JsonObject): String? {
        val labels = row["account_labels"] as? JsonObject
        val label = if (labels?.containsKey(head) == true) {
            JsonScalars.str(labels, head)
        } else {
            JsonScalars.str(row, "selector_key") ?: JsonScalars.str(row, ACCOUNT_LABEL_FIELD)
        }
        return (label ?: "primary").takeIf(AccountLabelPolicy::isSelector)
    }

    private fun native(head: String, row: JsonObject): NativeLoginHealth? {
        val place = (row["login_place"] as? JsonObject)?.let { JsonScalars.str(it, "id") } ?: return null
        val id = ClaudeLoginPlaceId.entries.singleOrNull { it.wire == place } ?: return null
        return NativeLoginHealth(
            head,
            id,
            expired = JsonScalars.str(row, "refusal")?.startsWith("Access token expired.") == true,
            present = JsonScalars.str(row, "credential_present") == "true",
        )
    }

    /** A null per-head label is the unkeyed login; pooled and native selectors retain their own verdict. */
    private fun refusalLabel(head: String, row: JsonObject): String? {
        val labels = row["account_labels"] as? JsonObject
        val raw = if (labels?.containsKey(head) == true) {
            JsonScalars.str(labels, head)
        } else {
            JsonScalars.str(row, "selector_key") ?: JsonScalars.str(row, ACCOUNT_LABEL_FIELD)
        }
        return raw?.takeIf(AccountLabelPolicy::isSelector)
    }

    private fun unkeyedRefusal(head: String, row: JsonObject): Boolean {
        val labels = row["account_labels"] as? JsonObject
        if (labels?.containsKey(head) == true) return labels[head] == JsonNull
        return row["selector_key"].let { it == null || it == JsonNull } &&
            row[ACCOUNT_LABEL_FIELD].let { it == null || it == JsonNull }
    }

    private fun refusal(row: JsonObject): ProviderAnswer? {
        val raw = row["last_refusal"] as? JsonObject ?: return null
        val statuses = setOf(
            HttpStatus.UNAUTHORIZED.toLong(),
            HttpStatus.FORBIDDEN.toLong(),
            HttpStatus.TOO_MANY_REQUESTS.toLong(),
        )
        val status = JsonScalars.long(raw, "status")?.takeIf { it in statuses }?.toInt() ?: return null
        return JsonScalars.long(raw, "at_ms")?.takeIf { it > 0L }?.let { at ->
            ProviderAnswer(status, at, quotaRefused = status == HttpStatus.TOO_MANY_REQUESTS)
        }
    }
}

/** Diagnoses only the roster's allowlisted facts. No native credential read or refresh is performed. */
internal object AccountHealthChecks {
    private val date = DateTimeFormatter.ofPattern("MMM d, h:mm a 'CT'", Locale.US)
        .withZone(ZoneId.of("America/Chicago"))

    fun checks(
        read: AccountPoolsRead.Read,
        authRefusals: Map<String, ProviderAnswer> = emptyMap(),
    ): List<DoctorCheck> =
        read.nativeLogins.filter { it.expired || !it.present }.map { login ->
            val current = read.pools[login.head]?.let { AccountPoolText().summary(it) }
            val standing = if (login.expired) {
                "has an expired access token and cannot take over"
            } else {
                "has no access token"
            }
            DoctorCheck(
                "native login ${login.place.command}",
                CheckStatus.WARN,
                listOfNotNull(
                    current?.let { "${login.head} is $it" },
                    "${login.place.command}'s login $standing",
                ).joinToString(". "),
                "Sign in again on ${login.place.command} in the console.",
            )
        } + read.lastRefusals.mapNotNull { (head, answer) ->
            refusal(head, answer).takeUnless { authRefusals[head] == answer }
        } + read.accountRefusals.flatMap { (head, accounts) ->
            accounts.map { (label, answer) -> refusal("$head account $label", answer) }
        }

    fun refusal(head: String, answer: ProviderAnswer): DoctorCheck = DoctorCheck(
        head,
        CheckStatus.WARN,
        "$head's newest retained provider refusal was HTTP ${answer.status} " +
            "at ${date.format(Instant.ofEpochMilli(answer.observedAtEpochMs))}",
        if (answer.status == HttpStatus.FORBIDDEN) {
            "Check the provider's permissions and subscription for $head."
        } else {
            null
        },
    )
}
