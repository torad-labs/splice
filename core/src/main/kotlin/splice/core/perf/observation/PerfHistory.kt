package splice.core.perf.observation

import kotlinx.serialization.Serializable

@Serializable
public data class PerfHistoryRead(
    val snapshots: List<PerfAggregateSnapshot>,
    val coverage: PerfCoverage,
    val resolutionMs: Long,
)

/** append acknowledges persistence; repeated cumulative minute points replace their older versions. */
public interface PerfHistory {
    public fun append(snapshot: PerfAggregateSnapshot): Boolean
    public fun read(fromMs: Long, untilMs: Long): PerfHistoryRead
    public fun status(): PerfSinkStatus
}
