// NEW: V4-457 trace reply projection stays separate from admission and filesystem read orchestration.
package splice.head.trace

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.util.JsonWire
import splice.head.TurnsHead
import splice.upstream.memory.JvmHeap

// why: growing encoder capacity, UTF-16 response text and HTTP byte conversion coexist.
private const val TRACE_REPLY_HEAP_FACTOR = 8L

/** Projects search hits and one turn's records without changing the stored trace. */
internal class TraceReplyBodies(private val heap: HeapReservations = JvmHeap.budget.readShare()) {
    private fun encode(record: JsonObject): String {
        val peak = heap.reserve(HeapWeights.multiply(JsonWire.byteSize(record), TRACE_REPLY_HEAP_FACTOR))
            ?: throw HeapCapacityException()
        peak.use {
            return JsonWire.string(record).also { HeapOwners.keep(it, peak.share()) }
        }
    }

    fun search(head: TurnsHead, found: TraceFound): String = buildJsonObject {
        put("back_to_epoch_ms", found.backTo)
        put("stopped_on", found.stoppedOn?.wire)
        val price = TurnPrice(head.catalog)
        putJsonArray("hits") { found.hits.forEach { add(hitJson(it, price)) } }
    }.let(::encode)

    /** A hit with the turn's own ending in the names /api/perf/turns gives a row, so one page draws both. */
    private fun hitJson(hit: TraceHit, price: TurnPrice): JsonObject = buildJsonObject {
        val end = hit.ending
        val known = PerfKeys.IN_TOKENS in end.counters && PerfKeys.OUT_TOKENS in end.counters
        put("turn", hit.turn)
        put("ts", hit.at)
        put("session", hit.session)
        put("where", hit.side.wire)
        put("text", hit.text)
        put("outcome", end.outcome)
        put("model", end.model)
        put("compact", end.compact)
        put("attempts", end.attempts)
        put("source_attempts", end.counters[PerfKeys.SOURCE_ATTEMPTS])
        put("total", end.marks[PerfKeys.TOTAL])
        put("first_byte", end.marks[PerfKeys.FIRST_BYTE])
        put("first_delta", end.marks[PerfKeys.FIRST_DELTA])
        put("admit_held_ms", end.counters[PerfKeys.ADMIT_HELD_MS])
        put("in_tokens", end.counters[PerfKeys.IN_TOKENS])
        put("out_tokens", end.counters[PerfKeys.OUT_TOKENS])
        put("cached_tokens", end.counters[PerfKeys.CACHED_TOKENS])
        put("cache_write_tokens", end.counters[PerfKeys.CACHE_WRITE_TOKENS])
        put("cost_usd", if (known) price.usd(end.model, end.counters, hit.at) else null)
    }

    fun turn(turn: TracedTurn): String = buildJsonObject {
        putJsonArray("records") {
            turn.attempts.forEach { add(it) }
            turn.turn?.let { add(it) }
        }
    }.let(::encode)
}
