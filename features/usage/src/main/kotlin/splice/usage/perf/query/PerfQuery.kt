package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfHistory
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.perf.runtime.PerfRuntimeSource
import splice.core.util.EnvReader
import splice.usage.perf.HeadAccountNames
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.history.PerfEvidenceSource

/** Half-open wall-clock interval. Filters never silently widen the requested population. */
public data class PerfQuery(
    val fromMs: Long,
    val untilMs: Long,
    val filters: Map<String, String> = emptyMap(),
    val objective: PerfObjectiveSet? = null,
    val source: String = "auto",
    val objectiveId: String? = null,
)

public data class PerfQueryRead(val query: PerfQuery?, val error: JsonObject?)

/** Revision identifiers describe effective configuration, never credential values. Null means unknown. */
public data class PerfConfigInterval(
    val head: String,
    val fromMs: Long,
    val untilMs: Long?,
    val revision: String?,
    val bootId: String? = null,
)

public fun interface PerfConfigProvenance {
    public fun intervals(fromMs: Long, untilMs: Long): List<PerfConfigInterval>
}

/** Offline implementations need only heads and rows; absent instruments remain explicit. */
public interface PerfQuerySources {
    public fun heads(): Set<String>
    public fun rows(head: String): PerfRowsSource?
    public fun live(): PerfAggregateSnapshot? = null
    public fun history(): PerfHistory? = null
    public fun evidence(): PerfEvidenceSource? = null
    public fun provenance(): PerfConfigProvenance? = null
    public fun runtime(): PerfRuntimeSource? = null

    /** Fresh local roster snapshot only; null means aliases cannot be resolved. Never refresh auth here. */
    public fun accountNames(head: String): HeadAccountNames? = null
    public fun objective(id: String): PerfObjectiveSet? = null

    /** Null means unavailable; an empty map explicitly identifies an unscoped named objective. */
    public fun objectiveCohort(id: String): Map<String, String>? = null
}

public fun interface HeadPerfQuery {
    public fun service(env: EnvReader): PerfQueryService
}

public fun interface PerfProfileCommand {
    public fun execute(args: List<String>): JsonObject
}
