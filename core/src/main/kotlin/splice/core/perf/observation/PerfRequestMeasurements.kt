package splice.core.perf.observation

/** Actual first-boundary measurements remain authoritative over caller-supplied terminal summaries. */
internal class PerfRequestMeasurements {
    private val measurements = LinkedHashMap<String, Double>()

    fun observe(kind: PerfEventKind, values: Map<String, Double>) {
        when (kind) {
            PerfEventKind.REQUEST_ADMITTED -> captureQueue(values)
            PerfEventKind.CONTENT_PROGRESS, PerfEventKind.CLIENT_PROGRESS -> captureBoundaries(kind, values)
            PerfEventKind.GAP -> captureGapMaxima(values)
            PerfEventKind.REQUEST_ARRIVED, PerfEventKind.REQUEST_FINISHED, PerfEventKind.ATTEMPT_STARTED,
            PerfEventKind.ATTEMPT_FINISHED, PerfEventKind.PROTOCOL_PROGRESS, PerfEventKind.GATE_STATE,
            PerfEventKind.TOOL_STARTED, PerfEventKind.TOOL_FINISHED, PerfEventKind.TOOL_PHASE,
            PerfEventKind.RUNTIME, PerfEventKind.SELF_COST, PerfEventKind.CHECKPOINT, PerfEventKind.USAGE_RECORDED,
            -> Unit
        }
    }

    fun terminal(values: Map<String, Double>, elapsedMs: Long): Map<String, Double> {
        val supplied = values.filterKeys {
            !PerfMetricRegistry.isBoundaryTiming(it) && !PerfMetricRegistry.isUsageMetric(it)
        }
        return supplied + measurements + ("arrival_to_terminal_ms" to elapsedMs.toDouble())
    }

    private fun captureQueue(values: Map<String, Double>) {
        values["queue_wait_ms"]?.takeIf {
            PerfMetricRegistry.accepts("queue_wait_ms", PerfEventKind.REQUEST_ADMITTED, it)
        }?.let { measurements.putIfAbsent("queue_wait_ms", it) }
    }

    private fun captureBoundaries(kind: PerfEventKind, values: Map<String, Double>) {
        values.forEach { (key, value) ->
            if (PerfMetricRegistry.isBoundaryTiming(key) && PerfMetricRegistry.accepts(key, kind, value)) {
                measurements.putIfAbsent(key, value)
            }
        }
    }

    private fun captureGapMaxima(values: Map<String, Double>) {
        listOf("upstream_gap_ms" to "upstream_gap_max_ms", "downstream_gap_ms" to "downstream_gap_max_ms")
            .forEach { (event, maximum) ->
                values[event]?.takeIf { PerfMetricRegistry.accepts(event, PerfEventKind.GAP, it) }?.let { value ->
                    measurements[maximum] = maxOf(measurements[maximum] ?: 0.0, value)
                }
            }
    }
}
