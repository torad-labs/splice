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

/** The frame AND the epoch it was built under, captured under ONE lock acquisition (F7). Two
 *  acquisitions — frameFor then epochOf — left a window where a concurrent [ResponsesWsSession.cleared]
 *  bumped the epoch AFTER the frame was built on now-invalidated context: the frame chained onto
 *  dropped state while its captured (post-bump) epoch still matched at commit, resurrecting exactly
 *  what cleared existed to bar (a bypassed SSE turn's messages then classify as server-held and
 *  silently vanish). Capturing both atomically closes it. */
internal data class WsFrameAndEpoch(val frame: WsFrame, val epoch: Long)

/**
 * Per-conversation chaining state for ONE provider. THREAD-SAFE: every method takes [lock].
 *
 * It used to say "not thread-safe by itself; the WS transport serializes rounds per conversation,
 * and every method here runs inside that serialized window." That contract was never sufficient for
 * this structure, and the 2026-08-11 outage is what it cost. The serialization the transport offers
 * is PER CONVERSATION; [chains] and [epochs] are GLOBAL maps shared by every conversation. Two
 * different conversations completing at the same instant both mutate the same LinkedHashMap — fully
 * permitted by that contract — which corrupts its internal list into a cycle. `trimLocked` makes it
 * unavoidable rather than merely possible: eviction touches keys belonging to OTHER conversations,
 * so even a strict per-key lock would not have serialized it.
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

    // BOUNDED, insertion-ordered, oldest dropped at the cap (review of #72's flow map: these had
    // no TTL or bound at all, so one daemon lifetime accumulated a record per conversation
    // forever). Dropping a record costs that conversation one full send — today's behaviour —
    // never a wrong chain: see epochOf for why eviction cannot resurrect stale state.
    private val chains = LinkedHashMap<String, Chain>()
    private val epochs = LinkedHashMap<String, Long>()

    /** THE lock for [chains], [epochs] and [seq]. Every method that reads or writes them holds it.
     *
     *  This existed only as a naming convention (`trimLocked`) until 2026-08-11, when a daemon that
     *  had been up 91h stopped serving: `completed`/`cleared` are driven from Netty event-loop
     *  threads, several conversations run at once, and concurrent mutation of a LinkedHashMap
     *  corrupts its internal list into a cycle. Six event loops were found spinning inside
     *  HashMap.remove at ~2 CPU-hours EACH — and because event loops are shared, every connection
     *  they served was accepted and never dispatched. The control plane stayed green throughout
     *  (different loop group), so health reported 4/4 ready while nothing could complete a turn. */
    private val lock = Any()

    /** A MONOTONIC counter, never per-key. Each [cleared] stamps a fresh value strictly greater
     *  than anything previously handed out, so a captured epoch can only still match when nothing
     *  invalidated the conversation in between. */
    private var seq = 0L

    /** The epoch for [key] — captured at send time, checked at commit time.
     *
     *  An ABSENT key falls back to the current [seq], not to zero, and that is what makes eviction
     *  safe: after a record is dropped under the cap, every previously captured epoch is strictly
     *  LESS than [seq] (a clear always stamps ++seq), so a late commit can never match and can
     *  never resurrect a chain the server no longer honours. Falling back to 0 would have
     *  re-opened exactly the ordering hole the epoch exists to close. */
    fun epochOf(key: String): Long = synchronized(lock) { epochs[key] ?: seq }

    /**
     * Build the frame for this round — the incremental one when every chaining precondition holds,
     * else the full frame. [request] is the full request the builder produced.
     *
     * TESTS ONLY; production must call [frameAndEpoch], and the `kt-ws-frame-without-epoch` wall
     * enforces that on src/main. Pairing this with a separate [epochOf] is the F7 defect: two lock
     * acquisitions leave a window where a concurrent [cleared] bumps the epoch after the frame was
     * built on invalidated context, and the captured post-bump epoch still matches at commit. There
     * is no correct way to use this inside a round; it survives only because ~17 unit tests build
     * frames without ever committing them, which is safe.
     */
    fun frameFor(key: String, request: JsonObject, generation: Long): WsFrame =
        frameAndEpoch(key, request, generation).frame

    /** Build the frame AND capture the commit epoch under ONE lock (F7) — the WS runner must use
     *  this, never frameFor + epochOf, or a concurrent clear between the two invalidates the chain
     *  after the frame is built while the captured epoch still matches at commit. */
    fun frameAndEpoch(key: String, request: JsonObject, generation: Long): WsFrameAndEpoch = synchronized(lock) {
        val chain = chains[key]
        val delta = if (chain == null) null else chainableDelta(chain, request, generation)
        val frame = if (chain == null || delta == null) {
            WsFrame(frame(request, previousResponseId = null, input = null), chained = false)
        } else {
            WsFrame(frame(request, previousResponseId = chain.responseId, input = JsonArray(delta)), chained = true)
        }
        WsFrameAndEpoch(frame, epochs[key] ?: seq)
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
    fun completed(
        key: String,
        request: JsonObject,
        responseId: String?,
        generation: Long,
        epoch: Long,
    ): Unit = synchronized(lock) {
        val input = request[FIELD_INPUT] as? JsonArray
        // A stale epoch means something invalidated this conversation while the round was in flight.
        val committable = responseId != null && input != null && epoch == (epochs[key] ?: seq)
        if (!committable) {
            // Committing now would anchor the next turn onto context the server lacks.
            chains.remove(key)
            return
        }
        chains.remove(key)
        chains[key] = Chain(input!!.map { it.toString() }, responseId!!, propsOf(request), generation)
        trimLocked()
    }

    /** Any non-clean ending (tear, cancel, failure, SSE fallback): the next round full-sends, AND
     *  any round still in flight is barred from committing (the epoch bump). */
    fun cleared(key: String): Unit = synchronized(lock) {
        chains.remove(key)
        epochs.remove(key)
        epochs[key] = ++seq
        trimLocked()
    }

    /** Drop the oldest records past the cap. Order is by last WRITE, which for a live conversation
     *  is every completed round, so an active one is never the eviction victim. */
    private fun trimLocked() {
        // Iterator removal, not `while (size > cap) remove(keys.first())`. The old shape re-entered
        // the map on every pass, so a map already corrupted by unsynchronized writes spun in
        // HashMap.remove forever instead of failing. The lock is what makes corruption impossible;
        // this is the shape that cannot spin even if that ever stops being true.
        evictOldest(chains.keys.iterator(), chains.size)
        evictOldest(epochs.keys.iterator(), epochs.size)
    }

    private fun evictOldest(keys: MutableIterator<String>, size: Int) {
        var over = size - MAX_CONVERSATIONS
        while (over > 0 && keys.hasNext()) {
            keys.next()
            keys.remove()
            over--
        }
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
        /** Same order as the reasoning cache's bound: far more than any real concurrent-session
         *  count on one head, and an evicted record costs only a full send. */
        const val MAX_CONVERSATIONS = 256

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
