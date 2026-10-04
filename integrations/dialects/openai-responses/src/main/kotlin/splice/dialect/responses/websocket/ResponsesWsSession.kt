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
package splice.dialect.responses.websocket

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.MonoClock

// WsFrame + WsFrameAndEpoch live in WsFrames.kt (concentration, 2026-08-19).

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
internal class ResponsesWsSession(
    private val maxConversations: Int = MAX_CONVERSATIONS,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val ttlMs: Long = TTL_MS,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {

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
        val bytes: Long,
        var at: Long,
        /** call_ids of the tool calls the committed response ENDED WITH — the server holds them as
         *  its context's tail and refuses any continuation that does not answer them ("No tool
         *  output found for function call …"). A turn that answers none of them cannot chain. */
        val pendingCalls: Set<String>,
    ) {
        /** Assistant text and calls observed on the committed server response. */
        var evidence: WsServerEvidence = WsServerEvidence()
    }

    // LRU + idle bounded. Chains retain the full logical input and request properties, so count
    // alone is not a memory bound; trimLocked enforces count, 64 MiB of retained UTF-8 input, and a
    // 30-minute idle TTL. Dropping a chain costs one full send — today's behaviour — never a wrong
    // delta: see epochOf for why eviction cannot resurrect stale state.
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
    private var totalBytes = 0L

    /** The epoch for [key] — captured at send time, checked at commit time.
     *
     *  DR-78: an absent key MATERIALIZES its own per-key epoch (a fresh ++seq) rather than
     *  reading the live global [seq] — the live read meant any OTHER conversation's clear bumped
     *  the value between capture and commit, voiding a never-cleared conversation's in-flight
     *  commit and silently defeating chaining for every concurrent conversation on each tear.
     *  Eviction safety is preserved: commit still falls back to the live [seq] for a key whose
     *  entry was dropped under the cap, and a clear always stamps ++seq, so a captured epoch can
     *  never match once anything invalidated the conversation. Falling back to 0 would have
     *  re-opened exactly the ordering hole the epoch exists to close. */
    fun epochOf(key: String): Long = synchronized(lock) { epochs.getOrPut(key) { ++seq } }

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
    fun frameAndEpoch(
        key: String,
        request: JsonObject,
        generation: Long,
        clientMetadata: JsonObject? = null,
    ): WsFrameAndEpoch = synchronized(lock) {
        val now = clock()
        trimLocked(now)
        val chain = chains.remove(key)?.also {
            it.at = now
            chains[key] = it
        }
        val delta = if (chain == null) null else chainableDelta(chain, request, generation)
        val frame = if (chain == null || delta == null) {
            // Claude Code's auto-compaction is the live case for this reason (2026-09-05): it
            // fires between a tool call and its execution, so the compaction body ends at the
            // PREVIOUS tool result and the call the server just emitted is never answered.
            // Chained, the server refused every one ("No tool output found for function call …")
            // and the round fell back to a cold SSE send that re-read the whole transcript (35%
            // cache); a full send on this socket keeps the prefix cache instead.
            val items = (request[FIELD_INPUT] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            val unanswered = chain?.let { unansweredCalls(it.pendingCalls, items) }.orEmpty()
            WsFrame(
                frame(request, previousResponseId = null, input = null, clientMetadata),
                chained = false,
                fullSendReason = unanswered.takeIf { it.isNotEmpty() }?.let {
                    "the server holds ${it.size} unanswered tool call(s) this turn never answers " +
                        "(${it.joinToString(", ")}); a chained send would be refused"
                },
            )
        } else {
            WsFrame(
                frame(request, previousResponseId = chain.responseId, input = JsonArray(delta), clientMetadata),
                chained = true,
            )
        }
        // DR-78: materialize the per-key epoch (see epochOf) — the live-seq fallback let an
        // unrelated conversation's clear void this key's commit.
        WsFrameAndEpoch(frame, epochs.getOrPut(key) { ++seq })
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
            ?.let { deltaOf(chain.input, it, chain.evidence) }
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { delta -> unansweredCalls(chain.pendingCalls, delta).isEmpty() }
    }

    /** The held calls [items] carry no answer for. Checked on the DELTA when chaining (the
     *  server-held prefix ends at the calls themselves, so the delta is the only place an answer
     *  can be) and on the whole input for the reason line — where the client's echo of the call
     *  itself (a `*_call` item with the same call_id) is not an answer (review 2026-09-05). */
    private fun unansweredCalls(pendingCalls: Set<String>, items: List<JsonObject>): List<String> =
        pendingCalls.filterNot { id ->
            items.any {
                JsonScalars.str(it[FIELD_CALL_ID]) == id &&
                    !JsonScalars.strOrEmpty(it[WS_FIELD_TYPE]).endsWith(CALL_SUFFIX)
            }
        }

    /** Commit after a clean terminal: the round's FULL logical input becomes the next turn's
     *  prefix (never the delta — the server now holds the chained context PLUS what we sent). */
    fun completed(
        key: String,
        request: JsonObject,
        responseId: String?,
        generation: Long,
        epoch: Long,
        pendingCalls: Set<String> = emptySet(),
        evidence: WsServerEvidence = WsServerEvidence(),
    ): Unit = synchronized(lock) {
        val now = clock()
        trimLocked(now)
        val input = request[FIELD_INPUT] as? JsonArray
        // A stale epoch means something invalidated this conversation while the round was in flight.
        val fresh = epoch == (epochs[key] ?: seq)
        // Unconditional in the old shape too — replacing the key moves it to the LRU tail.
        chains.remove(key)?.let { totalBytes -= it.bytes }
        // Two guards rather than one `committable` boolean: the null checks now sit in branches that
        // RETURN, so past them the compiler itself knows `responseId` and `input` are non-null.
        if (responseId == null || input == null) return
        if (!fresh) return
        val logicalInput = input.map(JsonWire::string)
        val props = propsOf(request)
        val bytes = logicalInput.sumOf(JsonWire::byteSize) +
            JsonWire.byteSize(props) +
            evidence.assistantTexts.sumOf(JsonWire::byteSize) +
            evidence.calls.values.sumOf(JsonWire::byteSize) +
            evidence.reasoning.entries.sumOf { (id, cipher) ->
                JsonWire.byteSize(id) + JsonWire.byteSize(cipher)
            }
        chains[key] = Chain(logicalInput, responseId, props, generation, bytes, now, pendingCalls).also {
            it.evidence = evidence
        }
        totalBytes += bytes
        trimLocked(now)
    }

    /** Any non-clean ending (tear, cancel, failure, SSE fallback): the next round full-sends, AND
     *  any round still in flight is barred from committing (the epoch bump). */
    fun cleared(key: String): Unit = synchronized(lock) {
        val now = clock()
        trimLocked(now)
        chains.remove(key)?.let { totalBytes -= it.bytes }
        epochs.remove(key)
        epochs[key] = ++seq
        trimLocked(now)
    }

    /** Expire idle chains wholesale, then restore both count and retained-input byte bounds. Order
     *  is least-recently-used: [frameAndEpoch] touches on use and [completed] writes at the tail. */
    private fun trimLocked(now: Long) {
        val cutoff = now - ttlMs
        val stale = chains.entries.iterator()
        while (stale.hasNext()) {
            val entry = stale.next()
            if (entry.value.at >= cutoff) break
            totalBytes -= entry.value.bytes
            stale.remove()
        }
        val bounded = chains.entries.iterator()
        var overBound = chains.size > maxConversations || totalBytes > maxTotalBytes
        while (overBound && bounded.hasNext()) {
            val entry = bounded.next()
            totalBytes -= entry.value.bytes
            bounded.remove()
            overBound = chains.size > maxConversations || totalBytes > maxTotalBytes
        }
        evictOldest(epochs.keys.iterator(), epochs.size)
    }

    private fun evictOldest(keys: MutableIterator<String>, size: Int) {
        var over = size - maxConversations
        while (over > 0 && keys.hasNext()) {
            keys.next().run { keys.remove() }
            over--
        }
    }

    /** [input] null = keep the request's own input array (the full send). */
    private fun frame(
        request: JsonObject,
        previousResponseId: String?,
        input: JsonArray?,
        clientMetadata: JsonObject?,
    ): String = buildJsonObject {
        put("type", "response.create")
        request.forEach { (k, v) -> if (!(k == FIELD_INPUT && input != null)) put(k, v) }
        if (input != null) put(FIELD_INPUT, input)
        if (clientMetadata != null) put("client_metadata", clientMetadata)
        if (previousResponseId != null) put("previous_response_id", previousResponseId)
    }.let(JsonWire::string)

    private fun propsOf(request: JsonObject): String =
        JsonObject(request.filterKeys { it != FIELD_INPUT }).let(JsonWire::string)

    /**
     * The suffix beyond [previous] that must be sent, or null to bail.
     *
     * Bails when the new input does not EXTEND the previous one elementwise (a rewritten
     * prefix means the builder changed history — cache-key drift, a compaction, an amended
     * body), or when the suffix holds any item that is neither a known server-held rebuild nor
     * a known client-new item. An assistant `message`, call or reasoning item is server-held
     * only when the committed response proves the bytes the client rebuilt.
     *
     * A member rather than the companion function it used to be (Kotlin main sources carry no
     * `companion` blocks); it reads only its arguments, and its one caller is [chainableDelta]
     * inside this class, so the call site is unchanged.
     */
    private fun deltaOf(
        previous: List<String>,
        current: List<JsonObject>,
        evidence: WsServerEvidence,
    ): List<JsonObject>? {
        if (!extendsPrefix(previous, current)) return null
        val send = mutableListOf<JsonObject>()
        val matchedCalls = mutableListOf<String>()
        val matchedReasoning = mutableListOf<String>()
        var matchedAssistant = 0
        var verified = true
        for (item in current.drop(previous.size)) {
            val assistantText = WsAssistantText.of(item)
            if (JsonScalars.str(item[WS_FIELD_TYPE]) in OBSERVED_SERVER_ITEMS) {
                // Neither a shared key nor a matching call id proves these response bytes.
                verified = verified && evidence.verifies(item)
                evidence.trackObserved(item, matchedCalls, matchedReasoning)
            } else if (assistantText != null) {
                // The first-prompt key identifies a scope, not which fork's assistant spoke.
                verified = verified && evidence.assistantTexts.getOrNull(matchedAssistant++) == assistantText
            } else {
                verified = verified && WsSuffixDisposition.accept(item, send)
            }
        }
        return send.takeIf { verified && evidence.completeEcho(matchedAssistant, matchedCalls, matchedReasoning) }
    }

    private fun extendsPrefix(previous: List<String>, current: List<JsonObject>): Boolean =
        current.size >= previous.size &&
            previous.indices.all { previous[it] == JsonWire.string(current[it]) }
}

/** Suffix shapes are separate from the session's retained connection state. Unknown ones bail. */
private object WsSuffixDisposition {
    private enum class Disposition { SERVER_HAS_IT, SEND, BAIL }

    fun accept(item: JsonObject, send: MutableList<JsonObject>): Boolean = when (dispositionOf(item)) {
        Disposition.SERVER_HAS_IT -> true
        Disposition.SEND -> {
            send += item
            true
        }
        Disposition.BAIL -> false
    }

    private fun dispositionOf(item: JsonObject): Disposition {
        val type = JsonScalars.str(item[WS_FIELD_TYPE]) ?: item[FIELD_ROLE]?.let { MESSAGE } ?: return Disposition.BAIL
        val assistantMessage = type == MESSAGE && JsonScalars.str(item[FIELD_ROLE]) == "assistant"
        return when {
            assistantMessage -> Disposition.BAIL
            type in OBSERVED_SERVER_ITEMS -> Disposition.SERVER_HAS_IT
            type in CLIENT_NEW -> Disposition.SEND
            else -> Disposition.BAIL
        }
    }
}

/** Far more than any real concurrent-session count; an eviction costs only a full send. */
private const val MAX_CONVERSATIONS = 256

/** Retained logical-input + property UTF-8 bytes across all chains on one head. */
private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

/** Idle, not absolute: frame construction and clean completion both refresh the chain. */
private const val TTL_MS = 30L * 60 * 1000

/** Item kinds the server ALREADY holds after the previous response: it produced them, so
 *  the builder's rebuild of them is a duplicate that must be dropped from the delta.
 *  `custom_tool_call` is code mode's `exec` (V4-446): since V4-388 it is the only tool a GPT
 *  model calls, and leaving it out bailed every code-mode round to a full send.
 *  FILE SCOPE ON PURPOSE: one shared immutable set, read per delta item. */
internal const val REASONING_ITEM_TYPE = "reasoning"
private val OBSERVED_SERVER_ITEMS = setOf(REASONING_ITEM_TYPE, "function_call", "custom_tool_call")

// Tool-search declarations are synthesized from this request's tool surface, not proof of an
// item the prior response emitted. An appended declaration must full-send rather than vanish.

/** Item kinds that are genuinely NEW client-side input and must be sent: a tool's output, an exec
 *  script's output (V4-446), a message. */
private val CLIENT_NEW = setOf("function_call_output", "custom_tool_call_output", "message")

private const val FIELD_INPUT = "input"
internal const val WS_FIELD_TYPE = "type"
internal const val FIELD_ROLE = "role"
internal const val FIELD_CALL_ID = "call_id"
private const val CALL_SUFFIX = "_call"
private const val MESSAGE = "message"
