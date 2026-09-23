// NEW: LAYOUT-01 — the head facts the usage routes consume: the quota snapshot, the perf and economics
// reads, and what the statusline renders with. The control plane adapts its wider ManagedHead into this
// projection, so the usage feature never depends upward on the control plane.
package splice.usage

import splice.accounts.pool.HeadAccountPoolSource
import splice.core.model.ClientWindows
import splice.core.model.ModelCatalog
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource

/** One head as the usage surfaces see it. Built per request by the adapter, never cached: [label]
 *  follows a runtime rename (DR-22a), and a projection captured at boot would freeze it. */
public data class UsageHead(
    /** The topology's key for this head — the name every payload is keyed by. */
    val key: String,
    val label: String,
    val usage: HeadUsageSource,
    val warnPct: Int,
    val warnTokens5h: Long,
    /** Per-turn perf telemetry rows for /api/perf; null = head has no perf sink wired. */
    val perf: HeadPerfSource? = null,
    /** The same rows with outcome tags, for the windowed summary and the per-turn view. */
    val perfRows: PerfRowsSource? = null,
    /** Hourly quota rollup for /api/economics; null = head has no economics sink wired. */
    val economics: HeadEconomicsSource? = null,
    /** Turns the statusline blob's client units back into the row's declared window and label. */
    val catalog: ModelCatalog? = null,
    /** Where the statusline records each session's real window. Null = a head that never learns. */
    val clientWindows: ClientWindows? = null,
    /** Head-local OAuth account selections and quotas, projected without credential material. */
    val accountPool: HeadAccountPoolSource? = null,
)

/** Every configured head, in topology order, read at CALL time. */
public fun interface UsageHeads {
    public fun all(): List<UsageHead>
}

/** The heads a by-name usage route resolves to: a KEY match first, then every wrapper-command (label)
 *  match. The caller decides what none and several mean — the lookup stays the control plane's, so
 *  every capability resolves a name the same way. */
public fun interface UsageHeadLookup {
    public fun byName(name: String): List<UsageHead>
}
