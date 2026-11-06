package splice.core.perf.observation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Stable numeric allowlist. Legacy fields are catalogued without being silently reinterpreted as new events. */
public object PerfMetricRegistry {
    private val definitions = (
        PerfLegacyMetrics.definitions() + PerfRequestMetrics.definitions() +
            PerfSystemMetrics.definitions()
        ).associateBy { it.id }
    private val bounds = listOf(
        BOUND_0_MS,
        BOUND_1_MS,
        BOUND_2_MS,
        BOUND_5_MS,
        BOUND_10_MS,
        BOUND_20_MS,
        BOUND_50_MS,
        BOUND_100_MS,
        BOUND_200_MS,
        BOUND_250_MS,
        BOUND_500_MS,
        BOUND_1000_MS,
        BOUND_2000_MS,
        BOUND_5000_MS,
        BOUND_10000_MS,
        BOUND_15000_MS,
        BOUND_30000_MS,
        BOUND_60000_MS,
        BOUND_120000_MS,
        BOUND_300000_MS,
        BOUND_600000_MS,
    )

    public fun schema(projection: PerfProjection = PerfProjection.LOCAL): JsonObject = buildJsonObject {
        put("schema_version", 1)
        put("projection", projection.name)
        put("duration_bounds_ms", JsonArray(bounds.map(::JsonPrimitive)))
        put(
            "histogram_precision",
            "upper-inclusive disjoint buckets; quantile error is bucket width; overflow has no bound",
        )
        put(
            "histogram_merge",
            "sum counts, sums and bucket counts; min of minima; max of maxima; never average quantiles",
        )
        put("snapshot_semantics", "cumulative minute replacements keyed by identity, metric, scope and fromMs")
        put("rate_denominator", "requested wall duration including idle time")
        put("max_series_per_family", PerfObservationLimits.MAX_SCOPES)
        put("max_aggregate_cells", PerfObservationLimits.MAX_CELLS)
        put("retained_minutes", PerfObservationLimits.RETAINED_MINUTES)
        put("gauge_sample_ms", PerfObservationLimits.SAMPLE_MS)
        put(
            "metrics",
            JsonObject(
                definitions.filterValues { projection in it.projections }.mapValues {
                    PerfMetricSchema.render(it.value)
                },
            ),
        )
    }

    public fun permits(metric: String, projection: PerfProjection): Boolean =
        definitions[metric]?.projections?.contains(projection) == true

    public fun durationBoundsMs(): List<Double> = bounds.toList()

    internal fun definition(metric: String): PerfMetricDefinition? = definitions[metric]

    internal fun isContentTiming(metric: String): Boolean = definitions[metric]?.let {
        it.type == PerfMetricType.HISTOGRAM && PerfEventKind.CONTENT_PROGRESS in it.events
    } == true

    internal fun accepts(metric: String, kind: PerfEventKind, value: Double): Boolean =
        value.isFinite() && value in 0.0..MAX_MEASUREMENT && definitions[metric]?.events?.contains(kind) == true
}

private const val MAX_MEASUREMENT = 1.0e18

private const val BOUND_0_MS = 0.0
private const val BOUND_1_MS = 1.0
private const val BOUND_2_MS = 2.0
private const val BOUND_5_MS = 5.0
private const val BOUND_10_MS = 10.0
private const val BOUND_20_MS = 20.0
private const val BOUND_50_MS = 50.0
private const val BOUND_100_MS = 100.0
private const val BOUND_200_MS = 200.0
private const val BOUND_250_MS = 250.0
private const val BOUND_500_MS = 500.0
private const val BOUND_1000_MS = 1000.0
private const val BOUND_2000_MS = 2000.0
private const val BOUND_5000_MS = 5000.0
private const val BOUND_10000_MS = 10000.0
private const val BOUND_15000_MS = 15000.0
private const val BOUND_30000_MS = 30000.0
private const val BOUND_60000_MS = 60000.0
private const val BOUND_120000_MS = 120000.0
private const val BOUND_300000_MS = 300000.0
private const val BOUND_600000_MS = 600000.0
