package splice.usage

import splice.accounts.pool.HeadAccountPoolSource
import splice.usage.economics.HeadEconomicsSource
import splice.usage.perf.HeadPerfSource
import splice.usage.perf.PerfRowsSource

/** The reads a head wires for the usage surfaces. Each is null when the head has no such sink wired. */
public data class UsageHeadSinks(
    /** Per-turn perf telemetry rows for /api/perf; null = head has no perf sink wired. */
    val perf: HeadPerfSource? = null,
    /** The same rows with outcome tags, for the windowed summary and the per-turn view. */
    val perfRows: PerfRowsSource? = null,
    /** Hourly quota rollup for /api/economics; null = head has no economics sink wired. */
    val economics: HeadEconomicsSource? = null,
    /** Head-local OAuth account selections and quotas, projected without credential material. */
    val accountPool: HeadAccountPoolSource? = null,
)
