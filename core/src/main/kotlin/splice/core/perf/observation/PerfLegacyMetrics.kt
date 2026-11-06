package splice.core.perf.observation

internal object PerfLegacyMetrics {
    fun definitions(): List<PerfMetricDefinition> =
        PerfLegacy1.definitions() + PerfLegacy2.definitions() + PerfLegacy3.definitions()
}
