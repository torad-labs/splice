package splice.core.perf.observation

/** Guarded by the owning request monitor. The first canonical bill wins, independently of client lifetime. */
internal class PerfCanonicalUsage(private val recorder: PerfRecorder) {
    private var pending: Map<String, Double>? = null
    private var terminal: PerfObservation? = null
    private var emitted = false

    fun record(values: Map<String, Double>): Boolean {
        if (pending != null || emitted) return false
        val usage = values.filterKeys(PerfMetricRegistry::isUsageMetric)
        if (!valid(usage) || usage.size != values.size) return false
        pending = usage
        return publish()
    }

    fun finish(observation: PerfObservation, values: Map<String, Double>): Boolean {
        terminal = observation.copy(values = emptyMap())
        if (pending == null) {
            val usage = values.filterKeys(PerfMetricRegistry::isUsageMetric)
            if (usage.isNotEmpty()) {
                if (!valid(usage)) return false
                pending = usage
            }
        }
        return publish()
    }

    private fun valid(values: Map<String, Double>): Boolean = values.isNotEmpty() && values.all { (metric, value) ->
        PerfMetricRegistry.accepts(metric, PerfEventKind.USAGE_RECORDED, value)
    }

    private fun publish(): Boolean {
        val ending = terminal ?: return true
        val values = pending ?: return true
        emitted = true
        pending = null
        if (recorder.elapsedSince(ending.stamp.monotonicMs) > 0) {
            recorder.emit(ending.scope, PerfEventKind.CHECKPOINT)
        }
        return recorder.publish(
            ending.copy(
                stamp = recorder.attributedStamp(ending.stamp),
                kind = PerfEventKind.USAGE_RECORDED,
                values = values,
            ),
        )
    }
}
