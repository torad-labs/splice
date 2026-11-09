package splice.core.perf.observation

import kotlinx.serialization.Serializable

/** Minute points are cumulative replacements keyed by boot/config/metric/scope/fromMs, never additive snapshots. */
@Serializable
public data class PerfAggregateSnapshot(
    val identity: PerfIdentity,
    val fromMs: Long,
    val untilMs: Long,
    val points: List<PerfAggregatePoint>,
    val coverage: PerfCoverage,
)

public fun interface PerfAggregateSource {
    public fun snapshot(): PerfAggregateSnapshot
}
