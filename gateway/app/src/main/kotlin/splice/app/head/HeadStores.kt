// NEW: a small parameter object so ManagedHeadFactory.assembleHead and HeadServerFactory can share
// a head's three file-backed stores without pushing either factory over detekt's LongParameterList
// ceiling. A data class, so detekt's LongParameterList exempts it (campaign claude-head decomposition).
package splice.app.head

import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore

internal data class HeadStores(
    val usageStore: UsageStore,
    val compactStats: CompactStats,
    val perfStats: PerfStats,
    val quota: QuotaTracker,
)
