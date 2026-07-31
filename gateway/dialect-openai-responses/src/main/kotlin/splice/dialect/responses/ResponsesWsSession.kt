// NEW: (ws-transport WS-2, 2026-07-31) previous_response_id chaining state + the delta classifier.
//
// THE POINT: 95.6% of claudex turns are tool round-trips, and each one re-sends the entire input
// array (~177k tokens, ~480KB) to collect a ~175-token tool call. Over a reused WebSocket the
// server keeps the previous response's context, so a continuation turn can send ONLY the items
// that are genuinely new (`previous_response_id` + the delta). What the server already holds, it
// must not be handed again — a re-sent item would DUPLICATE in its context, not dedupe.
//
// THE LAW (campaign ws-transport, BAIL-CLOSED DELTA): the incremental frame is emitted only when
// the new input is provably [everything the server already has] + [a suffix of exactly the shapes
// we understand]. Anything else — a rewritten prefix, an unrecognized item type anywhere in the
// suffix, a changed request property, a different connection generation — falls back to the FULL
// input in the same frame. A wrong drop silently loses context; a wrong send silently duplicates
// it; bailing costs only bytes we were already paying today. So every unknown bails.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.str

/** What to send on the wire for one WS round. [chained] is diagnostics + the WS-5 instrument: the
 *  count that must move when chaining engages, and must NOT when it bails. */
internal data class WsFrame(val json: String, val chained: Boolean)

/**
 * Per-conversation chaining state for ONE provider. Not thread-safe by itself; the WS transport
 * serializes rounds per conversation (one in-flight round per connection), and every method here
 * runs inside that serialized window.
 *
 * State is committed ONLY on a clean terminal ([completed]); any other ending clears it, so the
 * next round full-sends. That asymmetry is deliberate: an uncommitted response id is worthless,
 * while a stale one would chain onto context the server never finished building.
 */
internal class ResponsesWsSession {

    private data class Chain(
        /** The input array we last sent LOGICALLY (full history, not the delta) — the prefix any
         *  next turn must extend. Canonical strings, compared elementwise. */
        val input: List<String>,
        val responseId: String,
        /** Everything in the request except input (and the WS-only keys, which are never in the
         *  builder's output). The server pins these per response; a change (effort flip, tool
         *  surface change, instruction edit) must not ride a chained turn — codex gates connection
         *  reuse on the same set (responses_request_properties_match, client.rs:307). */
        val props: String,
        val generation: Long,
    )

    private val chains = HashMap<String, Chain>()

    /**
     * Build the frame for this round. [request] is the full request the builder produced.
     * Returns the incremental frame when every chaining precondition holds, else the full frame.
     */
    fun frameFor(key: String, request: JsonObject, generation: Long): WsFrame {
        val chain = chains[key]
        val delta = if (chain == null) null else chainableDelta(chain, request, generation)
        return if (chain == null || delta == null) {
            WsFrame(frame(request, previousResponseId = null, input = null), chained = false)
        } else {
            WsFrame(frame(request, previousResponseId = chain.responseId, input = JsonArray(delta)), chained = true)
        }
    }

    /** The items to send incrementally, or null when ANY precondition fails (bail closed). */
    private fun chainableDelta(chain: Chain, request: JsonObject, generation: Long): List<JsonObject>? {
        // Reconnect (server context died with the socket) or a changed pinned property.
        val reusable = chain.generation == generation && chain.props == propsOf(request)
        val array = (request[FIELD_INPUT] as? JsonArray)?.takeIf { reusable } ?: return null
        val items = array.filterIsInstance<JsonObject>()
        // A non-object item is a shape this classifier does not model, so bail: filterIsInstance
        // would otherwise silently drop it from the comparison and produce a WRONG delta.
        // An empty delta means the same input was re-sent (a client retry), not a continuation:
        // chaining it would ask the server to continue from a response with nothing to react to.
        return items.takeIf { it.size == array.size }
            ?.let { deltaOf(chain.input, it) }
            ?.takeIf { it.isNotEmpty() }
    }

    /** Commit after a clean terminal: the round's FULL logical input becomes the next turn's
     *  prefix (never the delta — the server now holds the chained context PLUS what we sent). */
    fun completed(key: String, request: JsonObject, responseId: String?, generation: Long) {
        val input = request[FIELD_INPUT] as? JsonArray
        if (responseId == null || input == null) {
            chains.remove(key)
            return
        }
        chains[key] = Chain(input.map { it.toString() }, responseId, propsOf(request), generation)
    }

    /** Any non-clean ending (tear, cancel, failure, SSE fallback): the next round full-sends. */
    fun cleared(key: String) {
        chains.remove(key)
    }

    /** [input] null = keep the request's own input array (the full send). */
    private fun frame(request: JsonObject, previousResponseId: String?, input: JsonArray?): String =
        buildJsonObject {
            put("type", "response.create")
            request.forEach { (k, v) -> if (!(k == FIELD_INPUT && input != null)) put(k, v) }
            if (input != null) put(FIELD_INPUT, input)
            if (previousResponseId != null) put("previous_response_id", previousResponseId)
        }.toString()

    private fun propsOf(request: JsonObject): String =
        JsonObject(request.filterKeys { it != FIELD_INPUT }).toString()

    internal companion object {
        /** Item kinds the server ALREADY holds after the previous response: it produced them, so
         *  the builder's rebuild of them is a duplicate that must be dropped from the delta. */
        private val SERVER_HELD = setOf("reasoning", "function_call", "tool_search_call", "tool_search_output")

        /** Item kinds that are genuinely NEW client-side input and must be sent. */
        private val CLIENT_NEW = setOf("function_call_output", "message")

        /**
         * The suffix beyond [previous] that must be sent, or null to bail.
         *
         * Bails when the new input does not EXTEND the previous one elementwise (a rewritten
         * prefix means the builder changed history — cache-key drift, a compaction, an amended
         * body), or when the suffix holds any item that is neither a known server-held rebuild nor
         * a known client-new item. An assistant `message` in the suffix is server-held (it produced
         * that text) — distinguished from a user message by role.
         */
        fun deltaOf(previous: List<String>, current: List<JsonObject>): List<JsonObject>? {
            if (!extendsPrefix(previous, current)) return null
            val send = mutableListOf<JsonObject>()
            for (item in current.drop(previous.size)) {
                when (dispositionOf(item)) {
                    Disposition.SERVER_HAS_IT -> Unit // it produced this; re-sending would duplicate
                    Disposition.SEND -> send += item
                    Disposition.BAIL -> return null
                }
            }
            return send
        }

        private fun extendsPrefix(previous: List<String>, current: List<JsonObject>): Boolean =
            current.size >= previous.size &&
                previous.indices.all { previous[it] == current[it].toString() }

        private enum class Disposition { SERVER_HAS_IT, SEND, BAIL }

        private fun dispositionOf(item: JsonObject): Disposition {
            // The builder emits plain role/content messages with no explicit `type`.
            val type = item[FIELD_TYPE].str() ?: item[FIELD_ROLE]?.let { MESSAGE } ?: return Disposition.BAIL
            val assistantMessage = type == MESSAGE && item[FIELD_ROLE].str() == "assistant"
            return when {
                type in SERVER_HELD || assistantMessage -> Disposition.SERVER_HAS_IT
                type in CLIENT_NEW -> Disposition.SEND
                else -> Disposition.BAIL // unknown shape
            }
        }

        private const val FIELD_INPUT = "input"
        private const val FIELD_TYPE = "type"
        private const val FIELD_ROLE = "role"
        private const val MESSAGE = "message"
    }
}
