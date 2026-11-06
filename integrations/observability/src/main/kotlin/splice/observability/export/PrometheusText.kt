package splice.observability.export

import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry

internal object PrometheusText {
    fun render(points: List<PerfAggregatePoint>, dropped: Long, missingMs: Long): String? =
        render(PrometheusBuffer(), points, dropped, missingMs)

    private fun render(
        out: PrometheusBuffer,
        points: List<PerfAggregatePoint>,
        dropped: Long,
        missingMs: Long,
    ): String? {
        points.groupBy { it.metric }.forEach { (id, family) ->
            val metric = ExportSchema.metrics.getValue(id)
            val name = metric.name + if (metric.type == "counter") "_total" else ""
            out.append("# TYPE ").append(name).append(' ').append(metric.type).append('\n')
            family.forEach { point ->
                if (out.exceeded) return null
                val labels = ExportSchema.attributes(
                    ExportSchema.scope(point.scope),
                ).asMap().entries.joinToString(",") {
                    "${it.key.key}=\"${it.value}\""
                }
                if (metric.type == "histogram") {
                    histogram(out, name, labels, point, metric.factor)
                } else {
                    sample(
                        out,
                        name,
                        labels,
                        (if (metric.type == "gauge") point.last else point.sum)?.times(metric.factor),
                    )
                }
            }
        }
        out.append(
            "# TYPE splice_export_dropped_total counter\nsplice_export_dropped_total ",
        ).append(dropped).append('\n')
        out.append("# TYPE splice_export_missing_seconds_total counter\nsplice_export_missing_seconds_total ")
            .append(missingMs / MILLIS_PER_SECOND).append('\n')
        return out.text()
    }

    private fun histogram(
        out: PrometheusBuffer,
        name: String,
        labels: String,
        point: PerfAggregatePoint,
        factor: Double,
    ) {
        val bounds = PerfMetricRegistry.durationBoundsMs()
        var cumulative = 0L
        point.buckets.forEachIndexed { i, count ->
            if (out.exceeded) return
            cumulative += count
            val bound = bounds.getOrNull(i)?.times(factor)?.toString() ?: "+Inf"
            sample(out, "${name}_bucket", "$labels,le=\"$bound\"", cumulative.toDouble())
        }
        sample(out, "${name}_sum", labels, point.sum * factor)
        sample(out, "${name}_count", labels, point.count.toDouble())
    }

    private fun sample(out: PrometheusBuffer, name: String, labels: String, value: Double?) {
        if (value == null || !value.isFinite()) return
        out.append(name).append('{').append(labels).append("} ").append(value).append('\n')
    }
}

private const val MILLIS_PER_SECOND = 1_000.0
