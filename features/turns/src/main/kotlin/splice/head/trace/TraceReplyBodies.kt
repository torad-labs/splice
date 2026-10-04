// NEW: V4-457 trace reply projection stays separate from admission and filesystem read orchestration.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.core.memory.HeapWeights
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.storage.DayFiles
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.head.TurnsHead
import splice.head.turn.OutcomeSentences
import splice.upstream.memory.JvmHeap
import java.nio.file.Path

// why: growing encoder capacity, UTF-16 response text and HTTP byte conversion coexist.
private const val TRACE_REPLY_HEAP_FACTOR = 8L

/** Projects summaries and selected records without changing the stored trace. */
internal class TraceReplyBodies(private val heap: HeapBudget = JvmHeap.budget) {
    private fun encode(record: JsonObject): String {
        val peak = heap.reserve(HeapWeights.multiply(JsonWire.byteSize(record), TRACE_REPLY_HEAP_FACTOR))
            ?: throw HeapCapacityException()
        peak.use {
            return JsonWire.string(record).also { HeapOwners.keep(it, peak.share()) }
        }
    }

    fun list(key: String, traceDir: Path, read: TraceRead): String =
        buildJsonObject {
            put("head", key)
            if (read.onDisk == 0 && DayFiles(traceDir, key).deleted()) {
                put("state", TRACE_DELETED_STATE)
                put("reason", TRACE_DELETED_REASON)
            }
            put("files", "$traceDir/$key-YYYY-MM-DD.jsonl")
            put("on_disk", read.onDisk)
            put("skipped_lines", read.skippedLines)
            put("unavailable_records", read.unavailableRecords)
            putJsonArray("turns") { read.turns.forEach { add(summary(it)) } }
        }.let(::encode)

    fun turn(head: TurnsHead, turn: TracedTurn): String = buildJsonObject {
        put("head", head.key)
        put("turn", summary(turn))
        // V4-345: an ended turn is priced by its model's card; missing counters leave cost unknown.
        turn.turn?.let { ending ->
            val price = TurnPrice(head.catalog)
            val counters = countersOf(ending)
            val known = PerfKeys.IN_TOKENS in counters && PerfKeys.OUT_TOKENS in counters
            put("cost_usd", if (known) price.usd(JsonScalars.str(ending, "model"), counters) else null)
        }
        putJsonArray("records") {
            turn.attempts.forEach { add(it) }
            turn.turn?.let { add(it) }
        }
    }.let(::encode)

    /** A turn record's perf counters, as stored by TurnTrace.turnRecord. */
    private fun countersOf(ending: JsonObject): Map<String, Long> {
        val counters = (ending["perf"] as? JsonObject)?.get("counters") as? JsonObject ?: return emptyMap()
        return buildMap { counters.keys.forEach { name -> JsonScalars.long(counters, name)?.let { put(name, it) } } }
    }

    /** The verb's table line as fields; an open turn uses the attempts still on disk. */
    private fun summary(turn: TracedTurn): JsonObject = buildJsonObject {
        val ending = turn.ending
        val open = turn.attempts.size
        put("id", turn.id)
        put("ts", turn.ts)
        put("session", turn.session)
        put("model", turn.model)
        put("compact", turn.compact)
        put("open", ending == null)
        put("outcome", ending?.outcome)
        // V4-414: legacy endings without a stored sentence use the same outcome sentence as the CLI.
        put("failure_sentence", ending?.let { it.failureSentence ?: OutcomeSentences.of(it.outcome) })
        put("rounds", if (ending == null) open.toLong() else ending.rounds.toLongOrNull())
        put("attempts", if (ending == null) open.toLong() else ending.attempts.toLongOrNull())
        put("total_ms", ending?.totalMs?.toLongOrNull())
    }
}
