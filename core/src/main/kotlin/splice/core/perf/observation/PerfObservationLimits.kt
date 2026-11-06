package splice.core.perf.observation

/** Count ceilings supplement the process-shared 16 MiB byte admission budget; they never create new allowances. */
internal object PerfObservationLimits {
    const val MAX_SCOPES = 256
    const val MAX_CELLS = 2048
    const val MAX_HANDLES = 1024
    const val MAX_DEDUP = 2048
    const val MAX_GAUGES = 512
    const val MAX_LABEL = 128
    const val RETAINED_MINUTES = 120
    const val MINUTE_MS = 60000L
    const val SAMPLE_MS = 5000L
}
