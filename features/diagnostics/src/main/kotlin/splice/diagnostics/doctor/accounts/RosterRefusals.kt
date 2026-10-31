// NEW: which provider refusal each login in Accounts' roster keeps for doctor: the unkeyed login's newest per head, and
// each keyed login's newest by its label. Moved out of AccountRosterProjection.kt, which keeps the pool views.
package splice.diagnostics.doctor.accounts

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.head.ProviderAnswer
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.credentials.AccountLabelPolicy

internal class RosterRefusals {
    /** Each head's newest refusal of its unkeyed login. */
    fun unkeyed(byHead: Map<String, List<JsonObject>>): Map<String, ProviderAnswer> =
        byHead.mapNotNull { (head, roster) ->
            roster.filter { unkeyedRefusal(head, it) }.mapNotNull(::refusal)
                .maxByOrNull { it.observedAtEpochMs }?.let { head to it }
        }.toMap()

    /** Each head's newest refusal of each keyed login, by its label; a head with none is left out. */
    fun keyed(byHead: Map<String, List<JsonObject>>): Map<String, Map<String, ProviderAnswer>> =
        byHead.mapValues { (head, roster) ->
            roster.mapNotNull { row ->
                val label = refusalLabel(head, row) ?: return@mapNotNull null
                refusal(row)?.let { label to it }
            }.groupBy({ it.first }, { it.second }).mapValues { (_, answers) ->
                answers.maxBy { it.observedAtEpochMs }
            }
        }.filterValues { it.isNotEmpty() }

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
