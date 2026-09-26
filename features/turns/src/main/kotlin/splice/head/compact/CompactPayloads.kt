// PORT-OF: ControlServer.kt (ControlPayloads.compactJson) @ a77531a — invariants unchanged: pure
// ManagedHead aggregation, which is why it never belonged next to the config/usage/perf readers.
package splice.head.compact

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.head.TurnsHeads

private const val COMPACT_TAIL = 50

// FILE SCOPE ON PURPOSE: one Set consulted per compact tail row — built once for the file, not
// rebuilt per CompactPayloads instance.
private val COMPACT_NUMERIC_FIELDS = setOf("ts", "chars", "ms", "status")

public class CompactPayloads(private val heads: TurnsHeads) {

    // Aggregate every head while retaining a head tag on each tail row. This is the dashboard's
    // actual CompactPayload contract: totals/outcomes plus a bounded newest-last event tail.
    public fun compactJson(): String {
        val summaries = heads.all().map { it to it.compact.summary(COMPACT_TAIL) }
        val outcomes = sum(summaries.map { (_, summary) -> summary.byOutcome })
        val recent = sum(summaries.map { (_, summary) -> summary.span?.recent.orEmpty() })
        val tail = summaries.flatMap { (managed, summary) ->
            summary.tail.map { row -> managed.key to row }
        }.sortedBy { (_, row) -> row["ts"]?.toLongOrNull() ?: 0L }
            .takeLast(COMPACT_TAIL)
        return buildJsonObject {
            putJsonObject("stats") {
                put("total", summaries.sumOf { (_, summary) -> summary.total })
                putCounts(this, "by_outcome", outcomes)
                putCounts(this, "by_outcome_7d", recent)
                putJsonObject("heads") {
                    summaries.forEach { (managed, summary) -> putHead(this, managed.key, summary) }
                }
                putJsonArray("tail") {
                    tail.forEach { (head, row) ->
                        addJsonObject {
                            put("head", head)
                            row.forEach { (key, value) -> putCompactScalar(this, key, value) }
                        }
                    }
                }
            }
        }.toString()
    }

    private fun sum(counts: List<Map<String, Int>>): Map<String, Int> {
        val total = LinkedHashMap<String, Int>()
        counts.forEach { map ->
            map.forEach { (outcome, count) -> total[outcome] = total.getOrDefault(outcome, 0) + count }
        }
        return total
    }

    private fun putCounts(sink: JsonObjectBuilder, name: String, counts: Map<String, Int>) {
        sink.putJsonObject(name) { counts.forEach { (outcome, count) -> put(outcome, count) } }
    }

    /** One head's counts beside the span they cover (console review 2026-09-24): each head's counts come
     *  from its own bounded tail, so only a per-head `first_ts` says honestly how far back they reach. A
     *  head with no rows carries no span. */
    private fun putHead(sink: JsonObjectBuilder, key: String, summary: CompactView) {
        sink.putJsonObject(key) {
            put("total", summary.total)
            putCounts(this, "by_outcome", summary.byOutcome)
            putCounts(this, "by_outcome_7d", summary.span?.recent.orEmpty())
            summary.span?.let { span ->
                put("first_ts", span.firstTs)
                put("last_ts", span.lastTs)
            }
        }
    }

    // ARGUMENT ORDER (HD-20): the former `JsonObjectBuilder` receiver became the first parameter;
    // [key] and [value] kept their order, so the sole call site's destructured
    // `(key, value)` pair still maps key -> key. Both are String — a swap compiles silently.
    private fun putCompactScalar(
        sink: JsonObjectBuilder,
        key: String,
        value: String,
    ) {
        val numeric = if (key in COMPACT_NUMERIC_FIELDS) value.toLongOrNull() else null
        if (numeric == null) sink.put(key, value) else sink.put(key, numeric)
    }
}
