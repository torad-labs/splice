package splice.app.head

import splice.head.compact.CompactStats
import splice.head.perf.PerfStats
import splice.head.usage.EconomicsStore

/** The per-turn record stores a head writes and the control plane reads, one instance each. */
internal data class HeadTelemetryStores(
    val compactStats: CompactStats,
    val perfStats: PerfStats,
    /** The hourly token-economics rollup (quota instrument): the head folds each finished turn into
     *  it and the control plane reads the SAME instance, so /api/economics never serves a second
     *  store's stale in-memory copy of the same file. */
    val economics: EconomicsStore,
)
