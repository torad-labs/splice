// NEW: a small parameter object so ManagedHeadFactory.assembleHead and HeadServerFactory can share
// a head's three file-backed stores without pushing either factory over detekt's LongParameterList
// ceiling. A data class, so detekt's LongParameterList exempts it (campaign claude-head decomposition).
package splice.app.head

import splice.core.model.ClientWindows
import splice.gateway.compact.CompactStats
import splice.gateway.perf.PerfStats
import splice.gateway.usage.EconomicsStore
import splice.gateway.usage.QuotaTracker
import splice.gateway.usage.UsageStore
import splice.gateway.wire.TraceStore
import splice.upstream.credentials.AccountPool

internal data class HeadStores(
    val usageStore: UsageStore,
    val compactStats: CompactStats,
    val perfStats: PerfStats,
    /** The hourly token-economics rollup (quota instrument): the head folds each finished turn into
     *  it and the control plane reads the SAME instance, so /api/economics never serves a second
     *  store's stale in-memory copy of the same file. */
    val economics: EconomicsStore,
    val quota: QuotaTracker,
    val accountPool: AccountPool? = null,
    val accountQuotas: Map<String, QuotaTracker> = emptyMap(),
    /** Per-session client windows (ClientWindows): written by the control plane's statusline
     *  route, read by the head's usage payload — one instance so both see the same sessions. */
    val clientWindows: ClientWindows = ClientWindows(),
    /** V4-174: the head's opt-in full trace; null is off. No default (the V4-105 law on the gateway
     *  twin): the one construction site decides from the head's own config, never by omission. */
    val trace: TraceStore?,
)
