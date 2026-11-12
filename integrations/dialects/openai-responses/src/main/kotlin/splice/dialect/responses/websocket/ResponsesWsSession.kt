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
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES,
    private val ttlMs: Long = TTL_MS,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    /** Which open calls belong to the provider's own runtime, so an ordinary turn leaves them alone. */
    private val policy: ResponsesWebSocketPolicy = ResponsesWebSocketPolicy(),
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

        /** The open calls [items] leaves unanswered that [policy]'s runtime runs itself (code mode's exec and wait). */
        fun runtimeOpen(items: List<JsonObject>, policy: ResponsesWebSocketPolicy): List<String> =
            WsChainingChecks.unansweredCalls(pendingCalls, items)
                .filter { id -> evidence.calls[id]?.let(policy::runsItself) == true }
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

    fun hasChain(key: String): Boolean = synchronized(lock) { chains.containsKey(key) }

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
    fun frameFor(key: String, request: JsonObject, generation: Long, compaction: Boolean = false): WsFrame =
        frameAndEpoch(key, request, generation, compaction = compaction).frame

    /** Build the frame AND capture the commit epoch under ONE lock (F7) — the WS runner must use
     *  this, never frameFor + epochOf, or a concurrent clear between the two invalidates the chain
     *  after the frame is built while the captured epoch still matches at commit. */
    fun frameAndEpoch(
        key: String,
        request: JsonObject,
        generation: Long,
        clientMetadata: JsonObject? = null,
        compaction: Boolean = false,
    ): WsFrameAndEpoch = synchronized(lock) {
        val now = clock()
        trimLocked(now)
        val chain = chains.remove(key)?.also {
            it.at = now
            chains[key] = it
        }
        val chained = if (chain == null) null else chainableDelta(chain, request, generation, compaction)
        val frame = if (chain == null || chained == null) {
            fullSend(chain, request, generation, clientMetadata, compaction)
        } else {
            WsFrame(
                frame(request, previousResponseId = chain.responseId, input = JsonArray(chained.send), clientMetadata),
                chained = true,
            )
        }
        // DR-78: materialize the per-key epoch (see epochOf) — the live-seq fallback let an
        // unrelated conversation's clear void this key's commit.
        WsFrameAndEpoch(
            frame,
            epochs.getOrPut(key) { ++seq },
            committed = chained?.committed(request),
        )
    }

    /** The whole input for a round that cannot chain, with the code and reason its full-send lines give. */
    private fun fullSend(
        chain: Chain?,
        request: JsonObject,
        generation: Long,
        clientMetadata: JsonObject?,
        compaction: Boolean,
    ): WsFrame {
        // Claude Code's auto-compaction was the live case for this reason (2026-09-05): it
        // fires between a tool call and its execution, so the compaction body ends at the
        // PREVIOUS tool result and the call the server just emitted is never answered.
        // Chained as it was, the server refused every one ("No tool output found for function
        // call …"). Since 2026-10-25 a compaction closes those calls and chains (WsCompactionClose), and since
        // 2026-10-27 every turn does. A full send here with calls left open is code mode's own call on an
        // ordinary turn, or a turn whose history the server never held.
        val items = (request[FIELD_INPUT] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        return WsFrame(
            frame(request, previousResponseId = null, input = null, clientMetadata),
            chained = false,
            fullSendCode = chain?.let { held ->
                WsFullSendDiagnostic.code(
                    WsSendExpectation(held.input, held.generation, held.props, held.pendingCalls),
                    request,
                    generation,
                    propsOf(request),
                ) { current -> deltaOf(held.input, current, held.evidence) }
            } ?: "no_prior_chain",
            fullSendReason = chain?.let {
                WsFullSendDiagnostic.reason(
                    it.runtimeOpen(items, policy),
                    WsChainingChecks.unansweredCalls(it.pendingCalls, items),
                    compaction,
                )
            },
        )
    }

    /** The items to send incrementally, or null when ANY precondition fails (bail closed). A
     *  [compaction] the ordinary delta refuses may still chain by closing the calls it left open. */
    private fun chainableDelta(
        chain: Chain,
        request: JsonObject,
        generation: Long,
        compaction: Boolean,
    ): WsChainedSend? {
        // Reconnect (server context died with the socket) or a changed pinned property.
        val reusable = chain.generation == generation && chain.props == propsOf(request)
        val array = (request[FIELD_INPUT] as? JsonArray)?.takeIf { reusable } ?: return null
        // A non-object item is a shape this classifier does not model, so bail: filterIsInstance
        // would otherwise silently drop it from the comparison and produce a WRONG delta.
        val items = array.filterIsInstance<JsonObject>().takeIf { it.size == array.size } ?: return null
        // An empty delta means the same input was re-sent (a client retry), not a continuation:
        // chaining it would ask the server to continue from a response with nothing to react to.
        val delta = deltaOf(chain.input, items, chain.evidence)
            ?.takeIf { it.isNotEmpty() }
            ?.takeIf { WsChainingChecks.unansweredCalls(chain.pendingCalls, it).isEmpty() }
        return when {
            delta != null -> WsChainedSend(delta)
            // codex-rs closes every call with no output (ensure_call_outputs_present). Code mode's own calls are
            // the exception on an ordinary turn: its runtime runs them, so they stay open and the turn full-sends.
            compaction || chain.runtimeOpen(items, policy).isEmpty() ->
                WsCompactionClose.close(chain.input, items, chain.pendingCalls, chain.evidence)
            else -> null
        }
    }

    /** Commit after a clean terminal: the round's FULL logical input becomes the next turn's
     *  prefix (never the delta — the server now holds the chained context PLUS what we sent). */
    fun completed(
        key: String,
        sent: ResponsesWsIdentity.PendingCommit,
        responseId: String?,
        pendingCalls: Set<String> = emptySet(),
        evidence: WsServerEvidence = WsServerEvidence(),
    ): Unit = synchronized(lock) {
        val request = sent.request
        val now = clock()
        trimLocked(now)
        val input = request[FIELD_INPUT] as? JsonArray
        // A stale epoch means something invalidated this conversation while the round was in flight.
        val fresh = sent.epoch == (epochs[key] ?: seq)
        // A stale terminal must not remove a newer response committed after this request was sent.
        if (!fresh) return
        chains.remove(key)?.let { totalBytes -= it.bytes }
        // Two guards rather than one `committable` boolean: the null checks now sit in branches that
        // RETURN, so past them the compiler itself knows `responseId` and `input` are non-null.
        if (responseId == null || input == null) {
            epochs[key] = ++seq
            return
        }
        // Digests, not text: the chain only ever asks whether a later input starts with these items.
        val logicalInput = input.map { WsChainingChecks.digest(JsonWire.string(it)) }
        val props = propsOf(request)
        val bytes = logicalInput.sumOf(JsonWire::byteSize) +
            JsonWire.byteSize(props) +
            evidence.assistantTexts.sumOf(JsonWire::byteSize) +
            evidence.calls.values.sumOf(JsonWire::byteSize) +
            evidence.reasoning.entries.sumOf { (id, cipher) ->
                JsonWire.byteSize(id) + JsonWire.byteSize(cipher)
            }
        chains[key] = Chain(logicalInput, responseId, props, sent.generation, bytes, now, pendingCalls).also {
            it.evidence = evidence
        }
        totalBytes += bytes
        // Fence other rounds that were built against the prior state. Only a frame built after
        // this commit may replace it; a late concurrent terminal cannot roll the chain backward.
        epochs[key] = ++seq
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

    /** Clear only the state this attempt observed; a newer commit owns a newer epoch. */
    fun clearedIfCurrent(key: String, expectedEpoch: Long): Boolean = synchronized(lock) {
        if ((epochs[key] ?: seq) != expectedEpoch) return false
        val now = clock()
        trimLocked(now)
        chains.remove(key)?.let { totalBytes -= it.bytes }
        epochs.remove(key)
        epochs[key] = ++seq
        trimLocked(now)
        true
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
        var overBound = totalBytes > maxTotalBytes
        while (overBound && bounded.hasNext()) {
            val entry = bounded.next()
            totalBytes -= entry.value.bytes
            bounded.remove()
            overBound = totalBytes > maxTotalBytes
        }
        evictOldest(epochs.keys.iterator(), epochs.size)
    }

    private fun evictOldest(keys: MutableIterator<String>, size: Int) {
        var over = size - MAX_TRACKED_EPOCHS
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
        JsonObject(request.filterKeys { it != FIELD_INPUT && it != FIELD_GENERATE }).let(JsonWire::string)
            .let(WsChainingChecks::digest)

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
        if (!WsChainingChecks.extendsPrefix(previous, current)) return null
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
}

/** Suffix shapes are separate from the session's retained connection state. Unknown ones bail. */
internal object WsSuffixDisposition {
    private enum class Disposition { SERVER_HAS_IT, SEND, BAIL }

    /** An item the server produced and so already holds: reasoning or a call. */
    fun isServerItem(item: JsonObject): Boolean = JsonScalars.str(item[WS_FIELD_TYPE]) in OBSERVED_SERVER_ITEMS

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

/** Conversations whose commit epoch is remembered. An epoch is a key and a number, so this costs a few MiB at most;
 *  forgetting a live conversation's epoch would drop its next commit, so it sits far past any real session count. */
private const val MAX_TRACKED_EPOCHS = 65_536

/** Retained bytes across all chains on one head: input and property digests plus the last answer's evidence. The
 *  input itself is never kept (2026-10-27): as text, 64 MiB held about forty long conversations. */
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

internal const val FIELD_INPUT = "input"
private const val FIELD_GENERATE = "generate"
internal const val WS_FIELD_TYPE = "type"
internal const val FIELD_ROLE = "role"
internal const val FIELD_CALL_ID = "call_id"
private const val MESSAGE = "message"
