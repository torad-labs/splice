// NEW: server mode (docs/specs/server-mode.md, F6). A snapshot of the server's per-person metrics as OTLP metric data:
// counters as cumulative monotonic sums, gauges as gauges, histograms with the same bounds and disjoint buckets, every
// point carrying its person and team. They go out under a scope of their own, `splice.server`, apart from the
// identity-free operator families (`splice.operator`).
package splice.observability.served

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableHistogramPointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData
import splice.core.server.MetricFamily
import splice.core.server.MetricLabels
import splice.core.server.MetricPoint
import splice.core.server.MetricSnapshot
import splice.observability.OBSERVABILITY_NANOS_PER_MILLISECOND
import splice.observability.export.ExportResource

/** Pinned OTel 1.68.0 immutable data factories, as the operator families use them (OtlpMetricData). */
internal class ServedMetricData {
    private val scope = InstrumentationScopeInfo.builder(SERVED_SCOPE).setVersion(SERVED_SCOPE_VERSION).build()

    /** Every family of [snapshot] that has a series. */
    fun of(snapshot: MetricSnapshot): List<MetricData> {
        val from = snapshot.sinceMs * OBSERVABILITY_NANOS_PER_MILLISECOND
        val at = snapshot.atMs * OBSERVABILITY_NANOS_PER_MILLISECOND
        return snapshot.families.mapNotNull { family ->
            when (family) {
                is MetricFamily.Counter -> family.points.takeIf(List<MetricPoint>::isNotEmpty)
                    ?.let { counter(family, it, from, at) }
                is MetricFamily.Gauge -> family.points.takeIf(List<MetricPoint>::isNotEmpty)
                    ?.let { gauge(family, it, from, at) }
                is MetricFamily.Histogram -> family.takeIf { it.points.isNotEmpty() }?.let { histogram(it, from, at) }
            }
        }
    }

    private fun counter(family: MetricFamily, points: List<MetricPoint>, from: Long, at: Long): MetricData =
        ImmutableMetricData.createDoubleSum(
            ExportResource.resource,
            scope,
            family.name,
            family.help,
            family.unit,
            ImmutableSumData.create(true, AggregationTemporality.CUMULATIVE, doubles(points, from, at)),
        )

    private fun gauge(family: MetricFamily, points: List<MetricPoint>, from: Long, at: Long): MetricData =
        ImmutableMetricData.createDoubleGauge(
            ExportResource.resource,
            scope,
            family.name,
            family.help,
            family.unit,
            ImmutableGaugeData.create(doubles(points, from, at)),
        )

    private fun histogram(family: MetricFamily.Histogram, from: Long, at: Long): MetricData =
        ImmutableMetricData.createDoubleHistogram(
            ExportResource.resource,
            scope,
            family.name,
            family.help,
            family.unit,
            ImmutableHistogramData.create(
                AggregationTemporality.CUMULATIVE,
                family.points.map { point ->
                    ImmutableHistogramPointData.create(
                        from, at, attributes(point.labels), point.sum,
                        false, 0.0, false, 0.0,
                        family.bounds, point.buckets,
                    )
                },
            ),
        )

    private fun doubles(points: List<MetricPoint>, from: Long, at: Long) =
        points.map { ImmutableDoublePointData.create(from, at, attributes(it.labels), it.value) }

    private fun attributes(labels: MetricLabels): Attributes {
        val built = Attributes.builder()
        labels.pairs.forEach { (name, value) -> built.put(AttributeKey.stringKey(name), value) }
        return built.build()
    }
}

private const val SERVED_SCOPE = "splice.server"
private const val SERVED_SCOPE_VERSION = "1"
