// NEW: provider names and head pools come from their authoritative sources, separate from OAuth joining.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.AccountHead

internal class AccountRowExtras(private val accounts: Map<String, AccountHead>) {
    /** Selection and hold facts stay per head even when credential-path joins combine roster rows. */
    fun pools(into: JsonObjectBuilder) {
        into.putJsonObject("head_pools") {
            accounts.values.forEach { head ->
                head.activePool?.let { pool ->
                    putJsonObject(head.key) { AccountPoolJson().write(this, pool.view(null)) }
                }
            }
        }
    }

    fun write(
        into: JsonObjectBuilder,
        labelsByHead: Map<String, String?>,
        providers: Map<String, String>,
    ) {
        into.put("provider", labelsByHead.keys.firstNotNullOfOrNull { providers[it] })
        into.putJsonObject("account_labels") {
            labelsByHead.forEach { (head, label) -> put(head, label) }
        }
    }
}
