// PORT-OF: ControlServer.kt (ControlPayloads.compactJson) @ a77531a — invariants unchanged: pure
// ManagedHead aggregation, which is why it never belonged next to the config/usage/perf readers.
package splice.control.api

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import splice.control.ManagedHead

private const val COMPACT_TAIL = 50

// FILE SCOPE ON PURPOSE: one Set consulted per compact tail row — built once for the file, not
// rebuilt per CompactPayloads instance.
private val COMPACT_NUMERIC_FIELDS = setOf("ts", "chars", "ms", "status")

internal class CompactPayloads(private val heads: Map<String, ManagedHead>) {

    // Aggregate every head while retaining a head tag on each tail row. This is the dashboard's
    // actual CompactPayload contract: totals/outcomes plus a bounded newest-last event tail.
    fun compactJson(): String {
        val summaries = heads.values.map { it to it.compact.summary(COMPACT_TAIL) }
        val outcomes = LinkedHashMap<String, Int>()
        summaries.forEach { (_, summary) ->
            summary.byOutcome.forEach { (outcome, count) ->
                outcomes[outcome] = outcomes.getOrDefault(outcome, 0) + count
            }
        }
        val tail = summaries.flatMap { (managed, summary) ->
            summary.tail.map { row -> managed.head.key to row }
        }.sortedBy { (_, row) -> row["ts"]?.toLongOrNull() ?: 0L }
            .takeLast(COMPACT_TAIL)
        return buildJsonObject {
            putJsonObject("stats") {
                put("total", summaries.sumOf { (_, summary) -> summary.total })
                putJsonObject("by_outcome") {
                    outcomes.forEach { (outcome, count) -> put(outcome, count) }
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

    // ARGUMENT ORDER (HD-20): the former `JsonObjectBuilder` receiver became the first parameter;
    // [key] and [value] kept their order, so the sole call site's destructured
    // `(key, value)` pair still maps key -> key. Both are String — a swap compiles silently.
    private fun putCompactScalar(
        sink: kotlinx.serialization.json.JsonObjectBuilder,
        key: String,
        value: String,
    ) {
        val numeric = if (key in COMPACT_NUMERIC_FIELDS) value.toLongOrNull() else null
        if (numeric == null) sink.put(key, value) else sink.put(key, numeric)
    }
}
