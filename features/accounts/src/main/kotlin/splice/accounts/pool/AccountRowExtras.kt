// NEW: provider names and pool positions come from their authoritative sources, separate from OAuth joining.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.AccountHead
import splice.accounts.order.HeadAccountOrderSource

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
        into.put("login_place", null as String?)
        into.put("account", null as String?)
        into.put("held", null as Boolean?)
        into.put("held_until_epoch_seconds", null as Long?)
        into.putJsonObject("failover_positions") {
            labelsByHead.toSortedMap().forEach { (head, label) ->
                val order = (accounts[head]?.pool as? HeadAccountOrderSource)?.effectiveOrder()
                val position = label?.let { order?.indexOf(it)?.takeIf { index -> index >= 0 } }
                put(head, position)
            }
        }
    }
}
