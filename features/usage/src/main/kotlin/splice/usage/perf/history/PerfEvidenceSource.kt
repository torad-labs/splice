package splice.usage.perf.history

import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfSinkStatus

/** Event-time bounds; callers join request ids and explicitly retain unknown arrivals/outcomes. */
public data class PerfEvidenceQuery(val fromMs: Long, val untilMs: Long, val head: String? = null)

public data class PerfEvidenceRead(
    val observations: List<PerfObservation>,
    val coverage: PerfCoverage,
    val asOfMs: Long,
    val complete: Boolean,
)

public fun interface PerfEvidenceSource {
    public fun read(query: PerfEvidenceQuery): PerfEvidenceRead
}

public fun interface PerfEvidenceSinkSource {
    public fun statuses(): List<PerfSinkStatus>
}
