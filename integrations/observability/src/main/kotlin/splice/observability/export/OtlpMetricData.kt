package splice.observability.export

import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfMetricRegistry

/** Pinned OTel 1.67.0 immutable data factories; upgrading the SDK requires these conversion tests. */
internal object OtlpMetricData {
    fun of(points: List<PerfAggregatePoint>): List<MetricData> = points.groupBy { it.metric }.map { (id, family) ->
        val metric = ExportSchema.metrics.getValue(id)
        when (metric.type) {
            "histogram" -> histogram(metric, family)
            "gauge" -> gauge(metric, family)
            else -> counter(metric, family)
        }
    }

    private fun histogram(metric: ExportMetric, family: List<PerfAggregatePoint>): MetricData =
        ImmutableMetricData.createDoubleHistogram(
            ExportResource.resource,
            ExportResource.scope,
            metric.name,
            metric.id,
            metric.unit,
            ImmutableHistogramData.create(
                AggregationTemporality.CUMULATIVE,
                family.map { point ->
                    ImmutableHistogramPointData.create(
                        point.fromMs * NANOS_PER_MS, point.untilMs * NANOS_PER_MS,
                        ExportSchema.attributes(point.scope), point.sum * metric.factor,
                        point.min != null, (point.min ?: 0.0) * metric.factor,
                        point.max != null, (point.max ?: 0.0) * metric.factor,
                        PerfMetricRegistry.durationBoundsMs().map { it * metric.factor }, point.buckets,
                    )
                },
            ),
        )

    private fun gauge(metric: ExportMetric, family: List<PerfAggregatePoint>): MetricData =
        ImmutableMetricData.createDoubleGauge(
            ExportResource.resource,
            ExportResource.scope,
            metric.name,
            metric.id,
            metric.unit,
            ImmutableGaugeData.create(
                family.mapNotNull { point ->
                    point.last?.let {
                        ImmutableDoublePointData.create(
                            point.fromMs * NANOS_PER_MS,
                            point.untilMs * NANOS_PER_MS,
                            ExportSchema.attributes(point.scope),
                            it * metric.factor,
                        )
                    }
                },
            ),
        )

    private fun counter(metric: ExportMetric, family: List<PerfAggregatePoint>): MetricData =
        ImmutableMetricData.createDoubleSum(
            ExportResource.resource,
            ExportResource.scope,
            metric.name,
            metric.id,
            metric.unit,
            ImmutableSumData.create(
                true,
                AggregationTemporality.CUMULATIVE,
                family.map { point ->
                    ImmutableDoublePointData.create(
                        point.fromMs * NANOS_PER_MS,
                        point.untilMs * NANOS_PER_MS,
                        ExportSchema.attributes(point.scope),
                        point.sum * metric.factor,
                    )
                },
            ),
        )
}
