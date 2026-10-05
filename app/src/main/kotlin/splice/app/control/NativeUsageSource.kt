// NEW: native windows come from Accounts' identity-joined port, never the head-wide tracker.
package splice.app.control

import splice.accounts.claude.ClaudeLoginPlaceId
import splice.accounts.claude.ClaudeLoginPlacesSource
import splice.accounts.pool.HeadAccountPoolView
import splice.app.auth.claude.OWN_SIGN_IN_LABEL
import splice.core.usage.QuotaSnapshot
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
        val selected = pool?.selectedLabel?.let { pool.selectedAccount() }
        val quota = if (pool?.selectionUnknown == true) {
            null
        } else if (selected != null && selected.label != OWN_SIGN_IN_LABEL) {
            pool.selectedQuota()
        } else {
            nativeQuota(pool)
        }
        // No native observation means no quota, never another credential's aggregate fallback.
        return head.usage.snapshot().copy(quota = quota)
    }

    private fun nativeQuota(pool: HeadAccountPoolView?): QuotaView? {
        val owner = native()
        val views = owner?.places().orEmpty().filter { it.head == head.head.key }
        val carrying = owner?.carrying(head.head.key)
        val carried = views.singleOrNull { it.id == carrying }?.quota
        return carried?.let(::quotaView) ?: if (pool?.accounts?.any { it.label.startsWith("native:") } == true) {
            pool.nextTargetLabel?.takeIf { target -> pool.accounts.any { it.label == target } }
                ?.let { pool.copy(selectedLabel = it).selectedQuota() }
        } else {
            views.singleOrNull { it.id == ClaudeLoginPlaceId.SPLICE }?.quota?.let(::quotaView)
        }
    }

    private fun quotaView(snapshot: QuotaSnapshot): QuotaView = QuotaView(
        snapshot.fiveHour?.let { window(it, snapshot.observedAtEpochSeconds) },
        snapshot.sevenDay?.let { window(it, snapshot.observedAtEpochSeconds) },
        snapshot.plan,
    )

    private fun window(window: QuotaWindow, observed: Long?): QuotaWindowView =
        QuotaWindowView(window.usedPercent.toInt(), window.resetsAt, observed, window.windowSeconds)
}
