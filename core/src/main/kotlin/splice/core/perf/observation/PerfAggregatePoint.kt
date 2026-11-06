package splice.core.perf.observation

import kotlinx.serialization.Serializable

/** Histogram buckets are disjoint upper-inclusive counts, with a final unbounded overflow bucket. */
@Serializable
public data class PerfAggregatePoint(
    val metric: String,
    val scope: PerfScope,
    val fromMs: Long,
    val untilMs: Long,
    val count: Long,
    val sum: Double,
    val min: Double?,
    val max: Double?,
    val buckets: List<Long> = emptyList(),
    val last: Double? = null,
    val weightedSum: Double = 0.0,
    val coveredMs: Long = 0,
)
