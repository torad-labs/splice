package splice.core.perf.observation

/** Conservative bounded state: < 32 MiB with 8,192 cells, fixed 22-slot histograms and bounded strings/handles. */
internal object PerfObservationLimits {
    const val MAX_SCOPES = 256
    const val MAX_CELLS = 8192
    const val MAX_HANDLES = 4096
    const val MAX_DEDUP = 8192
    const val MAX_LABEL = 128
    const val RETAINED_MINUTES = 120
    const val MINUTE_MS = 60000L
    const val SAMPLE_MS = 5000L
}
