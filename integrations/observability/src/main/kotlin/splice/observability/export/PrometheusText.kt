package splice.observability.export

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry

internal object PrometheusText {
    fun render(points: List<PerfAggregatePoint>, dropped: Long, missingMs: Long): String = buildString {
        points.groupBy { it.metric }.forEach { (id, family) ->
            val metric = ExportSchema.metrics.getValue(id)
            val name = metric.name + if (metric.type == "counter") "_total" else ""
            append("# TYPE ").append(name).append(' ').append(metric.type).append('\n')
            family.forEach { point ->
                val labels = ExportSchema.attributes(point.scope).asMap().entries.joinToString(",") {
                    "${it.key.key}=\"${it.value}\""
                }
                if (metric.type == "histogram") {
                    histogram(this, name, labels, point, metric.factor)
                } else {
                    sample(
                        this,
                        name,
                        labels,
                        (if (metric.type == "gauge") point.last else point.sum)?.times(metric.factor),
                    )
                }
            }
        }
        append("# TYPE splice_export_dropped_total counter\nsplice_export_dropped_total ").append(dropped).append('\n')
        append("# TYPE splice_export_missing_seconds_total counter\nsplice_export_missing_seconds_total ")
            .append(missingMs / MILLIS_PER_SECOND).append('\n')
    }

    private fun histogram(out: StringBuilder, name: String, labels: String, point: PerfAggregatePoint, factor: Double) {
        val bounds = PerfMetricRegistry.durationBoundsMs()
        var cumulative = 0L
        point.buckets.forEachIndexed { i, count ->
            cumulative += count
            val bound = bounds.getOrNull(i)?.times(factor)?.toString() ?: "+Inf"
            sample(out, "${name}_bucket", "$labels,le=\"$bound\"", cumulative.toDouble())
        }
        sample(out, "${name}_sum", labels, point.sum * factor)
        sample(out, "${name}_count", labels, point.count.toDouble())
    }

    private fun sample(out: StringBuilder, name: String, labels: String, value: Double?) {
        if (value == null || !value.isFinite()) return
        out.append(name).append('{').append(labels).append("} ").append(value).append('\n')
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
