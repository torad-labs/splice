// NEW: doctor and status project Accounts' authoritative roster, without retaining its private fields.
package splice.diagnostics.doctor.accounts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.pool.HeadAccountPoolView
import splice.accounts.pool.HeadAccountQuota
import splice.accounts.pool.HeadAccountView
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.diagnostics.doctor.AccountPoolProjection
import splice.diagnostics.doctor.AccountPoolsRead
import splice.diagnostics.doctor.FIX_RESTART
import splice.diagnostics.doctor.NativeLoginHealth
import splice.upstream.credentials.AccountLabelPolicy

// why: one roster key is shared by account identity and refusal ownership.
internal const val ACCOUNT_LABEL_FIELD = "label"

internal class AccountRosterProjection {
    private val pools = AccountPoolProjection()
    private val headKey = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val refusals = RosterRefusals()

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
        return AccountPoolsRead.Read(views, native, refusals.unkeyed(byHead))
            .also { it.accountRefusals = refusals.keyed(byHead) }
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
                    plan = account.plan,
                    quota = HeadAccountQuota(
                        fiveHour = pooled.quota.fiveHour.copy(
                            usedPercent = account.quota.fiveHour.usedPercent.takeUnless {
                                JsonScalars.str(row, "five_hour_current") == "false"
                            },
                            resetEpochSeconds = account.quota.fiveHour.resetEpochSeconds,
                        ),
                        sevenDay = pooled.quota.sevenDay.copy(
                            usedPercent = account.quota.sevenDay.usedPercent.takeUnless {
                                JsonScalars.str(row, "seven_day_current") == "false"
                            },
                            resetEpochSeconds = account.quota.sevenDay.resetEpochSeconds,
                        ),
                        observedAtEpochSeconds = account.quota.observedAtEpochSeconds,
                    ),
                    credential = pooled.credential.copy(present = account.credential.present),
                )
            } else {
                pooled.copy(credential = pooled.credential.copy(present = account.credential.present))
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
}
