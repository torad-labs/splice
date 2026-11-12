package splice.core.perf.observation

internal object PerfLegacyMetrics {
    const val COUNT_UNIT = "count"
    const val BYTES_UNIT = "bytes"

    fun definitions(): List<PerfMetricDefinition> =
        PerfLegacy1.definitions() + PerfLegacy2.definitions() + PerfLegacy3.definitions()
}
