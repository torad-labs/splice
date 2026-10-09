package splice.app.control

import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.HeadCompactSource
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.PerfRowsSource
import splice.usage.quota.HeadUsageSource

/** The per-capability file-backed sources the control plane reads for one head, even when it is DOWN. */
public data class HeadSources(
    val usage: HeadUsageSource,
    val compact: HeadCompactSource,
    val logs: HeadLogSource,
    /** Per-turn perf telemetry rows for /api/perf; null = head has no perf sink wired. */
    val perf: HeadPerfSource? = null,
    /** v0.4.0 (FEATURES.md §3): the same rows with outcome tags, for the windowed summary. */
    val perfRows: PerfRowsSource? = null,
    /** Hourly quota rollup for /api/economics; null = head has no economics sink wired. */
    val economics: HeadEconomicsSource? = null,
)
