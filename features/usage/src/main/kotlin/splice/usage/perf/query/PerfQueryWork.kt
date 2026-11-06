package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.query.PerfQueryJson.queryError
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/** A cache owns encoded bytes; every returned JSON tree owns a distinct lease. */
internal class PerfQueryWork(private val clock: WallClock) {
    private val lock = ReentrantLock()
    private var cached: CachedQuery? = null

    fun execute(query: PerfQuery, operation: String?, read: PerfQueryResult): JsonObject {
        val parsed = PerfQueryParser.parse(
            query.filters + listOfNotNull(query.objectiveId?.let { "objective" to it }).toMap() + mapOf(
                "from" to query.fromMs.toString(),
                "until" to query.untilMs.toString(),
                "source" to query.source,
            ),
            clock(),
        )
        if (parsed.error != null) return parsed.error
        if (!lock.tryLock()) return queryError("query_busy", "query_in_progress", "busy")
        return try {
            admitted(query, operation, read)
        } catch (_: IOException) {
            queryError("source_unreadable", "local_source_read_failed")
        } finally {
            lock.unlock()
        }
    }

    private fun admitted(query: PerfQuery, operation: String?, read: PerfQueryResult): JsonObject {
        val lease = PerfWorkBudget.acquire(QUERY_BYTES)
            ?: return queryError("memory_busy", "shared_work_budget", "busy")
        var retained = false
        return try {
            val result = result(query, operation, read)
            val bytes = result.toString().length.toLong() * JSON_CHARGE + RESULT_OVERHEAD
            if (bytes > QUERY_BYTES) return queryError("result_limit", "query_result_exceeds_budget", "partial")
            lease.retain(result, bytes)
            retained = true
            result
        } finally {
            if (!retained) lease.close()
        }
    }

    private fun result(query: PerfQuery, operation: String?, read: PerfQueryResult): JsonObject {
        val now = clock()
        val warm = cached?.takeIf { matches(it, query, operation, now) }
        val restored = warm?.let { JsonScalars.objectOrNull(Json, it.payload) }
        if (restored != null) return restored
        val result = read()
        if (operation != null) cache(query, operation, now, result)
        return result
    }

    private fun matches(entry: CachedQuery, query: PerfQuery, operation: String?, now: Long): Boolean =
        listOf(operation != null, entry.operation == operation, entry.query == query, now - entry.atMs in 0..CACHE_MS)
            .all { it }

    private fun cache(query: PerfQuery, operation: String, now: Long, result: JsonObject) {
        cached?.let(PerfWorkBudget::release)
        cached = null
        val encoded = result.toString()
        val bytes = encoded.length.toLong() * CHAR_CHARGE + RESULT_OVERHEAD
        val lease = PerfWorkBudget.acquire(bytes) ?: return
        val entry = CachedQuery(query, operation, now, encoded)
        lease.retain(entry, bytes)
        cached = entry
    }
}

internal fun interface PerfQueryResult {
    operator fun invoke(): JsonObject
}

private data class CachedQuery(val query: PerfQuery, val operation: String, val atMs: Long, val payload: String)
private const val QUERY_BYTES = 4L * 1_024 * 1_024
private const val JSON_CHARGE = 8L
private const val CHAR_CHARGE = 2L
private const val RESULT_OVERHEAD = 4_096L
private const val CACHE_MS = 1_000L
