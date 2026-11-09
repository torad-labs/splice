package splice.core.perf.observation.aggregate

/** Count ceilings supplement the process-shared 16 MiB byte admission budget; they never create new allowances. */
internal object PerfObservationLimits {
    // why: Cap the complete dimension product per metric family, including its explicit overflow scope.
    const val MAX_METRIC_SCOPES = 256

    // why: Bound sparse minute/histogram state across all metric families before byte admission.
    const val MAX_CELLS = 2048

    // why: Cap live request, attempt and tool bookkeeping independently of the shared byte budget.
    const val MAX_HANDLES = 1024

    // why: Keep duplicate-observation and terminal tombstones finite instead of retaining every lifetime identifier.
    const val MAX_DEDUP = 2048

    // why: Bound concurrently integrated gauge series so active resources cannot exhaust observation state.
    const val MAX_GAUGES = 512

    // why: Bound retained dimension/provenance strings before estimating their UTF-16 storage.
    const val MAX_LABEL = 128

    // why: Keep two hours of live minute aggregates; longer retention belongs to persisted history.
    const val RETAINED_MINUTES = 120

    // why: All cumulative aggregate replacements align to one-minute wall windows.
    const val AGGREGATE_MINUTE_MS = 60000L

    // why: The schema advertises a five-second gauge sampling cadence within those minute windows.
    const val GAUGE_SAMPLE_INTERVAL_MS = 5000L
}
