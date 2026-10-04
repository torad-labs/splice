// NEW: native windows come from Accounts' identity-joined port, never the head-wide tracker.
package splice.app.control

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.core.usage.QuotaView
import splice.core.usage.QuotaWindow
import splice.core.usage.QuotaWindowView
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView

/** Preserve counters and probe admission while reading the same native account snapshot as Accounts. */
internal class NativeUsageSource(
    private val head: ManagedHead,
    private val native: ClaudeLoginPlacesSource,
) : HeadUsageSource by head.usage {
    override fun snapshot(): UsageView {
        val pool = head.accountPool?.view(null)
        val selected = pool?.selectedAccount()
        val quota = if (pool?.selectionUnknown == true) {
            null
        } else if (selected != null && selected.label != OWN_SIGN_IN_LABEL) {
            pool.selectedQuota()
        } else {
            val place = native()?.places()?.singleOrNull {
                it.head == head.head.key && it.id == ClaudeLoginPlaceId.SPLICE
            }
            place?.quota?.let { snapshot ->
                val observed = snapshot.observedAtEpochSeconds
                QuotaView(
                    snapshot.fiveHour?.let { window(it, observed) },
                    snapshot.sevenDay?.let { window(it, observed) },
                    snapshot.plan,
                )
            }
        }
        // No native observation means no quota, never another credential's aggregate fallback.
        return head.usage.snapshot().copy(quota = quota)
    }

    private fun window(window: QuotaWindow, observed: Long?): QuotaWindowView =
        QuotaWindowView(window.usedPercent.toInt(), window.resetsAt, observed, window.windowSeconds)
}
