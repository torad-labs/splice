// NEW: LAYOUT-01 — control-owned adapters from ManagedHead into the usage feature's projection, so the
// usage routes read their head facts without importing ManagedHead or any other control-plane record.
package splice.control

import splice.control.api.HeadResolver
import splice.usage.UsageHead
import splice.usage.UsageHeadLookup
import splice.usage.UsageHeads

internal object UsageHeadAdapter {
    /** Adapted per call, never captured: a head's label follows a runtime rename (DR-22a). */
    fun heads(heads: Map<String, ManagedHead>): UsageHeads = UsageHeads { heads.values.map(::adapt) }

    /** The shared by-name lookup (key first, then every wrapper-command match). */
    fun lookup(resolver: HeadResolver): UsageHeadLookup =
        UsageHeadLookup { name -> resolver.headByName(name).map(::adapt) }

    private fun adapt(head: ManagedHead): UsageHead = UsageHead(
        key = head.head.key,
        label = head.head.label,
        usage = head.usage,
        warnPct = head.warnPct,
        warnTokens5h = head.warnTokens5h,
        perf = head.perf,
        perfRows = head.perfRows,
        economics = head.economics,
        catalog = head.catalog,
        clientWindows = head.clientWindows,
        accountPool = head.accountPool,
    )
}
