// NEW: the reasoning cache's two policy seams + the RC-4 item walk.
// Split from ReasoningCache.kt so the store is not billed for the
// request-amend walk (concentration, 2026-08-19). Same-package —
// callers keep splice.dialect.responses.ReasoningCachePolicy.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import splice.core.util.JsonScalars

/**
 * The reasoning cache's two policy seams, as a type rather than the file-level functions they used
 * to be (Kotlin main sources carry no top-level functions). Stateless; both members keep their old
 * name and argument list, so a call site only gained a receiver.
 */
internal class ReasoningCachePolicy {

    /** The gate for the RESPONSE-side reasoning-cache touch points (capture, collect):
     *  quirks-enabled AND not a compaction turn — a compaction's own reasoning is never stored.
     *  The REQUEST side (lookup, include-widening) deliberately does NOT route through here since
     *  2026-09-05: a compaction is built exactly like a turn, cached reasoning items included, or
     *  it shares no prompt-cache prefix with the session (ResponsesRequestBuilder.kt header). */
    fun reasoningCacheActive(quirks: ResponsesQuirks, compact: Boolean): Boolean =
        quirks.reasoningCache && !compact

    /** RC-4: the invalid_encrypted_content recovery — strip every reasoning input item from the
     *  request (degrade to per-item amnesia, never fail the turn on cache contents) and evict the
     *  cache for the rounds those items belonged to, i.e. the function_calls that immediately follow
     *  each dropped reasoning item up to the next one. Eviction is conversation-wholesale
     *  (2026-07-31, review of #71 round 2): the old per-round eviction left the surviving rounds
     *  injecting around a permanent hole, shifting the prefix on every later build.
     *  Returns null when the body carries no reasoning items (the amendment is not ours to make).
     *  Decode/encode rides the closed ResponsesRequest DTO (#924) — no field invented or lost. */
    fun stripStaleReasoning(bodyJson: String, cache: ReasoningCache): String? {
        val previous = kotlinx.serialization.json.Json.parseToJsonElement(bodyJson).jsonObject
        val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), previous)
        val walk = StaleReasoningWalk(cache)
        val kept = buildJsonArray { base.input.forEach { walk.visit(this, it) } }
        if (walk.dropped == 0) return null
        val next = base.copy(input = kept)
        return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next).toString()
    }
}

/** The strip's item walk: drop reasoning items, and evict the rounds they belonged to — a round
 *  is [reasoning, function_call+, …], so the scope is the unbroken run of calls right after a
 *  dropped item. (The cache widens each eviction to the whole conversation; see evictByToolId.) */
private class StaleReasoningWalk(private val cache: ReasoningCache) {
    var dropped = 0
    private var inDroppedRound = false

    fun visit(sink: JsonArrayBuilder, el: JsonElement) {
        val item = el as? JsonObject
        when (JsonScalars.str(item?.get(WALK_FIELD_TYPE))) {
            "reasoning" -> {
                dropped++
                inDroppedRound = true
            }
            "function_call" -> {
                sink.add(el)
                if (inDroppedRound) JsonScalars.str(item?.get("call_id"))?.let { cache.evictByToolId(it) }
            }
            else -> {
                sink.add(el)
                inDroppedRound = false
            }
        }
    }
}

private const val WALK_FIELD_TYPE = "type"

internal data class ReasoningCacheRound(
    val toolIds: List<String>,
    val envelopes: List<String>,
    val bytes: Long,
    val at: Long,
)

/** One conversation: rounds in arrival order, ONE idle timestamp, ONE admission flag. Every
 *  id of a round maps to that round; a lookup by ANY of them yields the round's ordered
 *  envelopes (inject-once stays the BUILDER's duty — this is a plain keyed store). */
internal class ReasoningCacheConvo {
    val rounds = LinkedHashMap<String, ReasoningCacheRound>()
    val byToolId = HashMap<String, String>()
    var bytes = 0L
    var at = 0L
    var frozen = false
}
