package splice.usage.perf.history

import kotlinx.serialization.json.JsonObject
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRow

public fun interface PerfRowPredicate {
    public fun matches(row: PerfRow): Boolean
}

public data class PerfCursorQuery(val fromMs: Long, val untilMs: Long, val filterHash: String)

public data class PerfCursorRequest(
    val query: PerfCursorQuery,
    val predicate: PerfRowPredicate,
    val cursor: String? = null,
    val pageSize: Int = 200,
)

public data class PerfCursorPage(
    val rows: List<PerfRow>,
    val recordIds: List<String>,
    val nextCursor: String?,
    val metadata: JsonObject,
) : AutoCloseable {
    override fun close(): Unit = PerfWorkBudget.release(this)
}

public interface PerfCursorSource {
    public fun page(request: PerfCursorRequest): PerfCursorPage
}
