// NEW: provider names and pool positions come from their authoritative sources, separate from OAuth joining.
package splice.accounts.pool

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.accounts.AccountHead
import splice.accounts.order.HeadAccountOrderSource

internal class AccountRowExtras(private val accounts: Map<String, AccountHead>) {
    fun write(
        into: JsonObjectBuilder,
        heads: Set<String>,
        label: String?,
        providers: Map<String, String>,
    ) {
        into.put("provider", heads.firstNotNullOfOrNull { providers[it] })
        into.put("login_place", null as String?)
        into.put("account", null as String?)
        into.put("held", null as Boolean?)
        into.put("held_until_epoch_seconds", null as Long?)
        into.putJsonObject("failover_positions") {
            heads.sorted().forEach { head ->
                val order = (accounts[head]?.pool as? HeadAccountOrderSource)?.effectiveOrder()
                val position = label?.let { order?.indexOf(it)?.takeIf { index -> index >= 0 } }
                put(head, position)
            }
        }
    }
}
