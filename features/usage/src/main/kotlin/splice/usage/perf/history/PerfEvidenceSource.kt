package splice.usage.perf.history

import kotlinx.serialization.json.JsonObject
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.PerfWorkBudget

/** Event-time bounds; callers join request ids and explicitly retain unknown arrivals/outcomes. */
public data class PerfEvidenceQuery(val fromMs: Long, val untilMs: Long, val head: String? = null)

public data class PerfEvidenceRead(
    val observations: List<PerfObservation>,
    val coverage: PerfCoverage,
    val asOfMs: Long,
    val complete: Boolean,
    val coverageBasis: String = "unknown",
    val eventTimeFinal: Boolean = false,
) : AutoCloseable {
    override fun close(): Unit = PerfWorkBudget.release(this)
}

public fun interface PerfEvidenceSource {
    public fun read(query: PerfEvidenceQuery): PerfEvidenceRead

    /** Consumers may explicitly select a completed window ending here; requested bounds are never rewritten. */
    public fun settledUntilMs(head: String? = null): Long? = null
}

public fun interface PerfEvidenceSinkSource {
    public fun statuses(): List<PerfSinkStatus>
}

/** Applied once while freezing a cursor population, never re-evaluated on continuation. */
public fun interface PerfObservationPredicate {
    public fun matches(observation: PerfObservation): Boolean
}

/** Pages contain events, never partially folded request rows. Join all pages by request and observation ids. */
public data class PerfEvidenceCursorRequest(
    val query: PerfEvidenceQuery,
    val cursor: String? = null,
    val pageSize: Int = 200,
    val predicate: PerfObservationPredicate? = null,
    /** Stable identity of the predicate selection; required when predicate is supplied. */
    val filterHash: String = "",
)

public data class PerfEvidencePage(
    val observations: List<PerfObservation>,
    val observationIds: List<String>,
    val nextCursor: String?,
    val metadata: JsonObject,
) : AutoCloseable {
    override fun close(): Unit = PerfWorkBudget.release(this)
}

public interface PerfEvidenceCursorSource : PerfEvidenceSource {
    public fun page(request: PerfEvidenceCursorRequest): PerfEvidencePage
}
