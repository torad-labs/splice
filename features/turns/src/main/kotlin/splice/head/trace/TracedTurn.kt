// NEW: one selected turn's columns, read from its exact attempt and terminal records.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import splice.core.perf.PerfKeys
import splice.core.util.JsonScalars

/** Attempts in wire order and the terminal record, absent while the turn remains open. */
internal data class TracedTurn(val id: String, val attempts: List<JsonObject>, val turn: JsonObject?) {
    init {
        require(turn != null || attempts.isNotEmpty()) { "traced turn $id holds no records" }
    }

    val first: JsonObject get() = turn ?: attempts.first()
    val ts: Long get() = JsonScalars.long(first, "ts") ?: 0L

    /** Earliest attempt opening bounds its perf row; missing timing cannot justify skipping history. */
    val startedAt: Long get() {
        val attempt = attempts.firstOrNull() ?: return 0L
        val at = JsonScalars.long(attempt, "ts") ?: return 0L
        val duration = JsonScalars.long(attempt, "durationMs") ?: return 0L
        return (at - duration.coerceAtLeast(0L)).coerceAtLeast(0L)
    }
    val session: String? get() = JsonScalars.str(first, "session")
    val model: String get() = JsonScalars.strOrEmpty(first["model"])
    val compact: Boolean get() = JsonScalars.str(first, "compact") == "true"

    /** The verb's table and console list read the same closing columns. */
    val ending: TurnEnding? get() = turn?.let { record ->
        val marks = record["perf"]?.jsonObject?.get("marks")?.jsonObject
        TurnEnding(
            outcome = JsonScalars.strOrEmpty(record["outcome"]),
            failureSentence = JsonScalars.str(record, "failure_sentence"),
            rounds = JsonScalars.strOrEmpty(record["rounds"]),
            attempts = JsonScalars.strOrEmpty(record["attempts"]),
            totalMs = marks?.let { JsonScalars.str(it, PerfKeys.TOTAL) },
        )
    }
}

/** Null total duration means the recorded performance marks contained no total. */
internal data class TurnEnding(
    val outcome: String,
    val failureSentence: String?,
    val rounds: String,
    val attempts: String,
    val totalMs: String?,
)
