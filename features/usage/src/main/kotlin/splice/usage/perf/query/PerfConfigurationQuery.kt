package splice.usage.perf.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.usage.perf.query.PerfQueryJson.numeric
import splice.usage.perf.query.PerfQueryJson.textValue

internal object PerfConfigurationQuery {
    fun render(sources: PerfQuerySources, query: PerfQuery, data: QueryData): JsonObject {
        val intervals = sources.provenance()?.intervals(query.fromMs, query.untilMs)
        val eligible = intervals.orEmpty().asSequence().filter { overlaps(it, query) }.take(MAX_INTERVALS + 1).toList()
        val boots = data.identities.map { it.bootId }.toSet()
        val unknown = incomplete(eligible, boots, sources, query)
        val revisions = eligible.mapNotNull { it.revision }.distinct()
        return buildJsonObject {
            put("status", when { unknown -> "unknown"; revisions.size > 1 -> "mixed"; else -> "known" })
            put("comparison_compatible", !unknown && revisions.size == 1)
            put("intervals", JsonArray(eligible.take(MAX_INTERVALS).map(::intervalJson)))
            put("truncated", eligible.size > MAX_INTERVALS)
        }
    }

    private fun overlaps(interval: PerfConfigInterval, query: PerfQuery): Boolean = listOf(
        interval.fromMs < query.untilMs,
        (interval.untilMs ?: query.untilMs) > query.fromMs,
        query.filters["head"]?.let { it == interval.head } != false,
    ).all { it }

    private fun incomplete(
        intervals: List<PerfConfigInterval>,
        boots: Set<String>,
        sources: PerfQuerySources,
        query: PerfQuery,
    ): Boolean =
        listOf(
            intervals.isEmpty(),
            intervals.size > MAX_INTERVALS,
            intervals.any { unknown(it, boots) },
            sources.heads().filter { query.filters["head"]?.let { asked -> it == asked } != false }.any {
                !covers(intervals.filter { interval -> interval.head == it }, boots, query)
            },
        ).any { it }

    private fun unknown(interval: PerfConfigInterval, boots: Set<String>): Boolean =
        interval.revision == null || interval.bootId !in boots

    private fun covers(intervals: List<PerfConfigInterval>, boots: Set<String>, query: PerfQuery): Boolean {
        var covered = query.fromMs
        intervals.sortedBy { it.fromMs }.forEach {
            if (it.fromMs <= covered && it.bootId in boots) covered = maxOf(covered, it.untilMs ?: query.untilMs)
        }
        return covered >= query.untilMs
    }

    private fun intervalJson(interval: PerfConfigInterval): JsonObject = buildJsonObject {
        put("head", interval.head)
        put("boot_id", textValue(interval.bootId))
        put("from_ms", interval.fromMs)
        put("until_ms", numeric(interval.untilMs))
        put("revision", textValue(interval.revision))
    }
}

private const val MAX_INTERVALS = 512
