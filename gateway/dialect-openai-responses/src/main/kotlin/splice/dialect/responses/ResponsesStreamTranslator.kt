// PORT-OF: server/src/codex/stream.mjs runStreamTurn @ pre-public-port-baseline, event-for-event — its comments
// are the spec; every rule below pins a shipped bug:
//   - tool_use opens EAGERLY on output_item.added; text/reasoning open LAZILY on first delta
//     (empty thinking widgets otherwise);
//   - reasoning summary PARTS join with "\n\n" into ONE thinking block; closing per part was
//     v24's truncation bug — blocks close only on output_item.done / the end sweep;
//   - *_text.done / *_part.done are IGNORED (fire per part);
//   - tool args stream as input_json_delta on the SAME wire block index;
//   - failure events are captured and the loop CONTINUES (the terminal decision happens after);
//   - replay (gated) emits redacted_thinking IN POSITION right after its item closes;
//   - harvest fallback merges the terminal object's text/thinking when deltas were sparse
//     (weak-text preference rules);
//   - honest failures: upstreamFailure -> classified; watchdog-fired -> overloaded; stream end
//     without response.completed -> ClientAbandoned if the client is gone, else truncated.
// RESPONSIBILITY SPLIT (pinned P2-MACH slot note): promote-to-text, empty-compact/empty-model
// honesty, mirror, and terminal emission live in the GATEWAY pipeline; buffers ride
// TurnOutcome.Success.
package splice.dialect.responses

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.SharedSummaryParts
import splice.core.turn.ToolSearchCall
import splice.core.turn.ToolSearchCallId
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.isWeakSummaryText
import splice.core.util.str
import splice.core.util.strIfString
import splice.core.util.strOrEmpty
import splice.spi.BufferCapacity
import splice.spi.ClassifiedFailure
import splice.spi.FailureSource
import splice.spi.StreamTranslator
import splice.spi.TerminalStates
import splice.spi.UpstreamFailureClassifier
import splice.spi.WatchdogFired
import splice.spi.WireSink
import splice.spi.terminalPrecedence
import java.io.IOException
import java.util.concurrent.CancellationException

/** Per-turn inputs the machine needs beyond the event flow. */
public data class StreamTurnContext(
    val compact: Boolean,
    /** EMIT redacted_thinking wire blocks when encrypted_content arrives (so Claude Code can
     *  store the opaque handle). NB: this is the STREAM-side emission flag — distinct from and
     *  opposite to BuildOptions.replayReasoning, which INJECTS prior reasoning into the request
     *  input. Same concept split into two honestly-named flags (craft review). */
    val emitEncryptedReasoning: EmitEncryptedReasoning,
    /** Encodes a reasoning item into the redacted_thinking envelope (gateway supplies; the
     *  envelope codec is splice-reasoning v1 and lands with P3-MIR). */
    val encodeReasoningEnvelope: (JsonObject) -> String?,
    /** True when the downstream client connection is already gone (client-abort detection). */
    val clientGone: () -> Boolean,
    /** The watchdog's typed sentinel, read AFTER the loop ends. */
    val watchdogFired: () -> WatchdogFired?,
    val streamIdleMsForMessage: Long,
    val upstreamTimeoutMsForMessage: Long,
    /** sequential_cutoff delivery restates earlier summary parts on every new reasoning item
     *  (probed 2026-07-19: part(1,0) byte-identical to part(0,0)); codex-rs dedups client-side.
     *  Gated to the delivery quirk so genuine token-granular streams are never touched. */
    val dedupeRepeatedSummaryParts: Boolean = false,
    /** Turn-scoped dedup state shared across this turn's continuation rounds (fresh translator
     *  per round; without it, a section re-titled by a continuation round passes each round's
     *  per-instance dedup and lands as a duplicate — the 2026-07-26 mirror duplication).
     *
     *  REQUIRED, no default (2026-07-27 review): it used to be `SharedSummaryParts? = null` for
     *  test convenience, and a null here silently restores exactly the per-round private state the
     *  turn-scoping exists to remove. Production passes `meta.summaryParts`; a test that wants
     *  round-private state now has to say so. */
    val summaryPartsShared: SharedSummaryParts,
    /** Encode this round's encrypted reasoning items into splice-reasoning envelopes, riding the
     *  Success outcome (fold replay) AND the Failure partial (re-anchor salvage, 2026-07-24).
     *  True for every fold- or re-anchor-eligible turn; off (compact) keeps the reducer
     *  collection-free. */
    val collectReasoningEnvelopes: Boolean = false,
    /** Reasoning-cache capture (RC-1, 2026-07-24): called once at a successful tool-use terminal
     *  with the round's REAL upstream function_call ids and its ordered reasoning envelopes —
     *  codex-rs parity (store:false full replay, client.rs:888/:915) held GATEWAY-side so the
     *  next tool-result request can reinject the model's plan in-position. Synthetic tool ids
     *  never key the cache (toolu_synth_* repeats across turns — cross-turn bleed). Default no-op
     *  keeps the reducer byte-identical when the provider wires no cache. */
    val onTurnReasoning: (toolIds: List<String>, envelopes: List<String>) -> Unit = { _, _ -> },
)

private const val PART_SEPARATOR = "\n\n"

// below this length an exact repeat is plausibly a genuine token fragment; whole summary parts
// (titled sections) are far longer
private const val SUMMARY_PART_DEDUP_MIN_CHARS = 20

// Per-item recap cursor sentinel: the leading cross-item recap has ended for this item, so every
// remaining part is genuinely new (only within-item exact repeats are still suppressed).
private const val RECAP_DONE = -1

// sequential_cutoff restatement dedup. This mode restates summary parts in TWO distinct ways, and
// one structure conflating them is why this kept oscillating (revert/reapply/rescope):
//   (A) CROSS-item recap — each NEW reasoning item replays every part emitted so far, IN ORDER, as
//       a leading prefix, then appends its genuinely-new parts (probed 2026-07-19: part(1,0) ==
//       part(0,0)). Suppressed by matching the leading run against the ordered emitted list via a
//       per-item cursor.
//   (B) WITHIN-item repeat — item.done can restate parts whose deltas then re-arrive, or vice versa
//       (openai/codex#16801 ordering anomaly, live 2026-07-19). Suppressed by a per-item exact set.
// A turn-global SET over-suppressed a paragraph two DISTINCT items coincidentally shared
// (2026-07-20); a per-ITEM set alone under-suppressed the cross-item recap (the duplication
// staircase). Splitting the two jobs keeps the coincidence (per-item, non-leading) while killing
// the staircase (ordered leading prefix). State + decision live together here (2026-07-23).
// The per-item sets are TURN-scoped per output_index SLOT (2026-07-26): continuation rounds share
// them and the backend restarts output_index at 0 each round, so a byte-identical non-leading part
// shared by two genuinely different items landing in the same slot is suppressed across rounds.
// Accepted (operator call): an exact identity is overwhelmingly a restatement, and no-duplicates
// wins. Re-keying the sets by round or item id is the one fix NOT to make — when the backend
// re-titles a repeat into the same slot, that keying is what lets the mirror duplication back in.
// The 2026-07-20 case stays intact: WITHIN a round, distinct items still get distinct slots.
private class SummaryDedup(private val active: Boolean, private val shared: SharedSummaryParts) {
    // The emitted parts are TURN-scoped: the caller hands over the turn's one SharedSummaryParts
    // (TurnMeta's default), so continuation rounds see each other's emissions. recapCursor is
    // deliberately per round — each round's stream re-starts its leading recap at position 0 of
    // the shared list, which is exactly what suppresses it.
    private val recapCursor = HashMap<Int, Int>()

    /** True to SUPPRESS a recap/repeat part of item [oi]; a genuinely-new part returns false and is
     *  recorded so later items' recaps (A) and this item's own re-arrivals (B) match. Parts under
     *  the min length always pass and are never recorded (plausibly genuine token fragments). */
    fun suppress(oi: Int, part: String): Boolean {
        if (!active || part.length < SUMMARY_PART_DEDUP_MIN_CHARS) return false
        val cursor = recapCursor.getOrDefault(oi, 0)
        if (shared.partAt(cursor) == part) {
            recapCursor[oi] = cursor + 1
            return true
        }
        recapCursor[oi] = RECAP_DONE
        return !shared.markEmitted(oi, part)
    }
}

// Mutable per-block cursor. A data class here documents it as pure per-block state; it is only
// ever stored/looked up by wire index, never compared by value.
private data class BlockState(
    val index: WireBlockIndex,
    var sawDelta: Boolean,
    // CX-01: accumulated tool-argument text for a function_call block, validated as JSON at close.
    // Empty for text/reasoning blocks (they never reach onArgs). Capped by BufferCapacity (NF-06).
    val args: StringBuilder = StringBuilder(),
)

public class ResponsesStreamTranslator(private val ctx: StreamTurnContext) : StreamTranslator {

    // NF-06: latched when BufferCapacity trips; never provider-reported (the verdict is local).
    private var runawayGuard: String? = null

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        // Stream read errors surface via the terminal decision, never a crash; only a genuine
        // cancellation (no watchdog fire) is allowed to propagate.
        val reducer = ResponsesEventReducer(ctx)
        try {
            upstream.collect { evt ->
                // NF-06: the shared runaway valve Chat already had — reasoningEnvelopes accumulate
                // before any block opens, so they ride the pendingArgs surface of the cap.
                val envelopeChars = reducer.reasoningEnvelopes.sumOf { it.length }
                val over = BufferCapacity.over(
                    reducer.textBuf.length,
                    reducer.thinkingBuf.length,
                    pendingArgsLen = envelopeChars,
                )
                if (over) {
                    runawayGuard = RUNAWAY_GUARD_MESSAGE
                    return@collect
                }
                reducer.onEvent(evt, sink)
            }
        } catch (e: CancellationException) {
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // upstream read error: fall through to the honest terminal decision
        } catch (ignored: SerializationException) {
            // malformed upstream frame: fall through to the honest terminal decision
        } catch (ignored: IllegalArgumentException) {
            // malformed value in a frame: fall through to the honest terminal decision
        }

        sink.closeAll()
        harvestFallback(reducer)
        val outcome = terminalOutcome(reducer)
        captureTurnReasoning(reducer, outcome)
        return outcome
    }

    /** RC-1 capture: only a SUCCESSFUL tool-use round seeds the reasoning cache — the client
     *  will come back with these tool ids and the injection needs the plan that produced them. */
    private fun captureTurnReasoning(reducer: ResponsesEventReducer, outcome: TurnOutcome) {
        if (outcome !is TurnOutcome.Success || !outcome.hasToolUse) return
        if (reducer.turnToolIds.isEmpty() || reducer.reasoningEnvelopes.isEmpty()) return
        ctx.onTurnReasoning(reducer.turnToolIds.toList(), reducer.reasoningEnvelopes.toList())
    }

    // Ordering enforced by the shared spi.terminalPrecedence: a COMPLETED response wins over a
    // late watchdog fire (the watchdog polls the whole enclosing coroutine, which stays suspended
    // on the socket-EOF read AFTER response.completed was already parsed — discarding that turn
    // would retry a successful compaction, the exact quota waste the watchdog exists to prevent).
    private fun terminalOutcome(reducer: ResponsesEventReducer): TurnOutcome = terminalPrecedence(
        TerminalStates(
            // NF-06: a tripped runaway valve outranks everything — the buffers were truncated, so
            // neither a late terminal nor a provider error can describe this turn honestly.
            providerFailure = runawayGuard?.let {
                TurnOutcome.Failure(ErrorType.API_ERROR, it, providerReported = false)
            } ?: reducer.toolArgsInvalid?.let {
                // CX-01: the backend sent a terminal, but a tool call's arguments are corrupt —
                // provider-reported (the backend produced the bytes) so it retries, never a clean
                // Success that dispatches garbage.
                TurnOutcome.Failure(
                    ErrorType.API_ERROR,
                    "ChatGPT backend: $it in tool call — retry",
                    providerReported = true,
                )
            } ?: reducer.upstreamFailure?.let {
                // parsed from a response.failed/error event the backend actually sent (G20 provenance)
                TurnOutcome.Failure(
                    it.type,
                    "ChatGPT backend: ${it.message}",
                    providerReported = true,
                    partial = partialOrNull(reducer),
                )
            } ?: refusalFailure(reducer) ?: contentFilterFailure(reducer),
            finished = reducer.finalResponse != null,
            watchdogFired = ctx.watchdogFired(),
        ),
        onFinished = { successOutcome(reducer) },
        onWatchdog = ::watchdogOutcome,
        onUnfinished = { noCompletionOutcome(reducer) },
    )

    // W4-A (L3): the backend REFUSED. OpenAI's refusal channel arrives with status `completed`, so
    // the incomplete-only gate below never sees it and the turn used to end as a clean Success
    // carrying zero text — or worse, as a normal turn once the pipeline promoted the streamed chain
    // of thought to the answer. The model's stated reason IS the verdict.
    // Deliberately carries NO `partial`, unlike every other failure here: a refusal is
    // deterministic, and ResponsesReanchorController re-POSTs any API_ERROR that carries salvage
    // (RETRYABLE = {OVERLOADED, API_ERROR}), so a partial would buy an identical refusal at full
    // upstream cost.
    private fun refusalFailure(reducer: ResponsesEventReducer): TurnOutcome.Failure? =
        reducer.refusalBuf.toString().takeIf { it.isNotBlank() }?.let {
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: model refused — $it",
                providerReported = true, // the `refusal` the backend sent, not a local verdict (G20)
            )
        }

    // response.incomplete with a non-max_output_tokens reason is a CENSORED turn — a clean
    // Success(incomplete=true) would let a blocked generation masquerade as complete (the same
    // L3 honesty invariant ChatStreamTranslator's contentFiltered branch closes, line 97-105).
    private fun contentFilterFailure(reducer: ResponsesEventReducer): TurnOutcome.Failure? =
        if (reducer.contentFiltered) {
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: generation stopped by content filter",
                providerReported = true,
                partial = partialOrNull(reducer),
            )
        } else {
            null
        }

    private fun noCompletionOutcome(reducer: ResponsesEventReducer): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "claudex: upstream stream ended without response.completed (truncated); retry",
                partial = partialOrNull(reducer),
            )
        }

    /** The salvage payload for mid-stream re-anchoring — the wire is at a block boundary
     *  (driveTurn closeAll precedes the terminal decision); watchdog failures never carry one
     *  (their turn coroutine is being cancelled — nothing may re-POST). Compact turns never
     *  re-anchor, so skip the full-buffer copies for them. */
    private fun partialOrNull(reducer: ResponsesEventReducer): TurnOutcome.PartialRound? =
        if (ctx.compact) null else partialRound(reducer)

    private fun partialRound(reducer: ResponsesEventReducer): TurnOutcome.PartialRound = TurnOutcome.PartialRound(
        thinkingText = reducer.thinkingBuf.toString(),
        bodyText = reducer.textBuf.toString(),
        emittedText = reducer.emittedText,
        emittedThinking = reducer.emittedThinking,
        hasToolUse = reducer.hasToolUse,
        reasoningEnvelopes = reducer.reasoningEnvelopes.toList(),
        toolTearOpen = reducer.toolSalvage.tearOpen,
        usage = Usage(
            inputTokens = reducer.inputTokens,
            outputTokens = reducer.outputTokens,
            cachedTokens = reducer.cachedTokens,
            reasoningTokens = reducer.reasoningTokens,
        ),
    )

    private fun watchdogOutcome(fired: WatchdogFired): TurnOutcome {
        val why = when (fired) {
            is WatchdogFired.Idle ->
                "no completion within the ${ctx.streamIdleMsForMessage / MS_PER_S}s idle cap"
            is WatchdogFired.TotalCap ->
                "no completion within the ${ctx.upstreamTimeoutMsForMessage / MS_PER_S}s total cap"
        }
        return TurnOutcome.Failure(
            ErrorType.OVERLOADED,
            "claudex: upstream stream stalled ($why) — aborted; retry",
        )
    }

    private fun successOutcome(reducer: ResponsesEventReducer): TurnOutcome = TurnOutcome.Success(
        hasToolUse = reducer.hasToolUse,
        incomplete = reducer.incomplete,
        usage = Usage(
            reducer.inputTokens,
            reducer.outputTokens,
            reducer.cachedTokens,
            reducer.reasoningTokens,
        ),
        thinkingText = reducer.thinkingBuf.toString(),
        bodyText = reducer.textBuf.toString(),
        emittedText = reducer.emittedText,
        emittedThinking = reducer.emittedThinking,
        reasoningEnvelopes = reducer.reasoningEnvelopes.toList(),
        // The harvest fallback runs ONLY when the streamed list is empty (needs no dedup): a round
        // that emitted only a search call and was missed by the live capture would otherwise
        // produce a client-visible empty turn through the honesty gate — the worst available failure.
        toolSearches = reducer.toolSearches.ifEmpty { harvestToolSearchCalls(reducer.finalResponse) },
    )

    /** W4-A: the completed response's `refusal`-typed content parts — the third refusal carrier,
     *  and the only one a backend that streams neither `response.refusal.delta` nor
     *  `response.refusal.done` uses. A stream that never reaches a terminal has no
     *  [ResponsesEventReducer.finalResponse] and already fails as truncated, so reading the
     *  terminal object here loses nothing. The part object carries `refusal` and no `delta`, so
     *  [addRefusal] routes it to the whole-copy branch on its own. */
    private fun harvestRefusalParts(reducer: ResponsesEventReducer, resp: JsonObject) {
        val output = resp["output"] as? JsonArray ?: return
        output.forEach { item ->
            val content = (item as? JsonObject)?.get("content") as? JsonArray ?: return@forEach
            content.forEach { part ->
                val obj = part as? JsonObject ?: return@forEach
                if (strOrEmpty(obj["type"]) == "refusal") reducer.addRefusal(obj)
            }
        }
    }

    private fun harvestFallback(reducer: ResponsesEventReducer) {
        val resp = reducer.finalResponse ?: return
        harvestRefusalParts(reducer, resp)
        val harvested = harvestResponsesOutput(resp)
        // CharSequence checks avoid an intermediate toString() when the streamed buffer is large
        // and the harvest path is a no-op (the common case once deltas have been flowing).
        if (shouldPreferHarvestedText(reducer.textBuf, harvested.text)) {
            reducer.textBuf = StringBuilder(harvested.text)
        }
        if (harvested.thinking.length > reducer.thinkingBuf.length) {
            reducer.thinkingBuf = StringBuilder(harvested.thinking)
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}

/**
 * Folds the upstream SSE event stream into per-turn buffers/flags. One private handler per
 * upstream event family — the ported shape of stream.mjs's runStreamTurn; the translator drives
 * it and reads the accumulated state to render the terminal outcome.
 */
private class ResponsesEventReducer(val ctx: StreamTurnContext) {

    // Int keys: message/tool blocks use the upstream output_index directly; reasoning blocks
    // use REASONING_KEY_BASE + output_index so the two families never collide and we never
    // allocate "reasoning:$oi" / oi.toString() strings on the hot path.
    val blocks = HashMap<Int, BlockState>()
    var hasToolUse = false
    var emittedText = false

    /** CX-09: a thinking block actually reached the sink. NOT the same as thinkingBuf being
     *  non-empty — [harvestFallback] refills that buffer from the completed response object
     *  without emitting anything, and that is precisely the turn the honesty gate must still
     *  call empty. */
    var emittedThinking = false
    var incomplete = false

    // response.incomplete carrying a non-max_output_tokens reason (content_filter, etc.) — the
    // L3 honesty hole ChatStreamTranslator's contentFiltered branch closes for the chat dialect.
    var contentFiltered = false

    // W4-A (L3): an OpenAI REFUSAL, which is a different wire path from the content filter above —
    // it rides `status: completed`, so [contentFiltered] can never see it. THREE carriers, all
    // previously unread (`grep refusal` over this module returned nothing): the
    // `response.refusal.delta` event, the `response.refusal.done` event that finalizes the refusal
    // text, and a `refusal`-typed content part on the completed item.
    val refusalBuf = StringBuilder()
    var thinkingBuf = StringBuilder()
    var textBuf = StringBuilder()
    var finalResponse: JsonObject? = null
    var upstreamFailure: ClassifiedFailure? = null

    // CX-01: latched when a function_call block closes with malformed/empty argument JSON — a
    // truncated-but-terminated tool call would otherwise reach Claude Code as a corrupt tool_use.
    var toolArgsInvalid: String? = null
    var inputTokens = 0L
    var outputTokens = 0L
    var cachedTokens = 0L
    var reasoningTokens = 0L

    // splice-reasoning envelopes of this round's encrypted reasoning items (fold replay). Collected
    // only when ctx.collectReasoningEnvelopes — otherwise stays empty, pre-fold behaviour intact.
    val reasoningEnvelopes = mutableListOf<String>()
    private var toolSynthCounter = 0

    // Mid-stream re-anchor salvage (eli 2026-07-24) — see [ToolSalvage].
    val toolSalvage = ToolSalvage()

    // Reasoning-cache capture (RC-1): the round's REAL upstream function_call ids, in order.
    val turnToolIds = mutableListOf<String>()

    // tool_search_call items this round emitted (deferred tool surface) — populated only when the
    // gateway declared deferral this turn; empty otherwise (pre-deferral behaviour intact).
    val toolSearches = mutableListOf<ToolSearchCall>()

    // Late-reasoning items already emitted, keyed by their reasoning block index. Substring
    // dedup on thinkingBuf dropped a DISTINCT item whose text happened to be a substring of an
    // earlier one, diverging wire from mirror (audit 2026-07-18); track per item instead.
    private val emittedReasoningKeys = HashSet<Int>()

    // sequential_cutoff restatement dedup — state + decision encapsulated in SummaryDedup (above).
    private val summaryDedup = SummaryDedup(ctx.dedupeRepeatedSummaryParts, ctx.summaryPartsShared)

    suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (strOrEmpty(evt["type"])) {
            "response.completed", "response.done", "response.incomplete" -> onTerminal(evt)
            "response.failed", "response.error", "error" -> onFailure(evt)
            else -> onStreamEvent(evt, sink)
        }
    }

    private suspend fun onStreamEvent(evt: JsonObject, sink: WireSink) {
        when (strOrEmpty(evt["type"])) {
            "response.output_item.added" -> onItemAdded(evt, sink)
            "response.output_item.done" -> onItemDone(evt, sink)
            "response.reasoning_summary_part.added" -> onSummaryPartAdded(evt, sink)
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                onThinkingDelta(evt, sink)
            "response.output_text.delta" -> onTextDelta(evt, sink)
            "response.function_call_arguments.delta", "response.function_call_arguments.done" ->
                onArgs(evt, sink)
            // W4-A (L3): the streamed refusal carriers. Both used to fall into the `else` below
            // and the turn finished clean with no text at all. `.done` is not an edge case — the
            // documented order is refusal.delta xN -> refusal.done -> content_part.done, and
            // ResponseRefusalDoneEvent carries the COMPLETE refusal string, so a backend that
            // finalizes without streaming deltas is covered only here. ONE two-label arm calling
            // addRefusal(evt): onStreamEvent sits at cyclomatic 9 of detekt's 10, so a second arm
            // (or an elvis picking the carrier here) fails the budget — the carrier selection
            // lives inside addRefusal instead. The literal "delta" is used there rather than the
            // DELTA constant, which is private to this class's companion.
            "response.refusal.delta", "response.refusal.done" -> addRefusal(evt)
            // *_text.done / *_part.done fire PER PART — closing here was the v24 truncation bug
            else -> Unit
        }
    }

    private fun onTerminal(evt: JsonObject) {
        val resp = (evt["response"] as? JsonObject) ?: evt
        finalResponse = resp
        if (strOrEmpty(evt["type"]) == "response.incomplete" || strOrEmpty(resp["status"]) == "incomplete") {
            incomplete = true
            val reason = (resp["incomplete_details"] as? JsonObject)?.str("reason").orEmpty()
            // max_output_tokens is the honest "ran out of room" stop; any other reason
            // (content_filter, etc.) is a censored generation, never a clean incomplete.
            if (reason.isNotEmpty() && reason != INCOMPLETE_REASON_MAX_TOKENS) contentFiltered = true
        }
        accumulateUsage(this, resp)
    }

    private fun onFailure(evt: JsonObject) {
        // v25 honesty: failure events were silently discarded and the turn finished as a
        // clean empty end-of-turn, corrupting the transcript. Capture; keep reading.
        val e = (evt["response"] as? JsonObject)?.get("error") as? JsonObject
            ?: evt["error"] as? JsonObject
            ?: evt
        val code = strOrEmpty(e["code"]).ifEmpty { strOrEmpty(e["type"]) }.ifEmpty { "upstream_failed" }
        val message = strOrEmpty(e["message"]).ifEmpty { "ChatGPT backend reported failure" }
        upstreamFailure = UpstreamFailureClassifier.classify(FailureSource.SSE, "$code $message")
        // A response.failed payload can carry the round's usage — harvest it so the salvage
        // accounting is real (code-review 2026-07-24: the terminal-only harvest left
        // PartialRound.usage permanently zero).
        (evt["response"] as? JsonObject)?.let { accumulateUsage(this, it) }
    }

    private suspend fun onItemAdded(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject ?: return
        val oi = intOr(evt[OUTPUT_INDEX]) ?: intOr(item["index"]) ?: blocks.size
        if (strOrEmpty(item["type"]) == "function_call") {
            // A JsonNull call_id/name must not leak onto the wire as the literal string "null" —
            // strOrEmpty keeps both filtered so the empty-fallback chain below still triggers
            // (review 2026-07-22 round 3).
            val rawId = strOrEmpty(item["call_id"]).ifEmpty { strOrEmpty(item["id"]) }
            val id = rawId.ifEmpty { "toolu_synth_${toolSynthCounter++}_$oi" }
            if (rawId.isNotEmpty()) turnToolIds.add(rawId)
            val idx = sink.openTool(id = id, name = strOrEmpty(item["name"]))
            blocks[oi] = BlockState(idx, sawDelta = false)
            hasToolUse = true
            toolSalvage.opened(oi)
        }
        // reasoning + message (text) blocks open lazily on their first delta —
        // avoids empty thinking widgets when a reasoning item carries no summary
    }

    // Split into onItemDone + 2 file-level extensions below (detekt 2026-07-24: CyclomaticComplexMethod
    // 11 -> the class already sits at TooManyFunctions' 14-member ceiling, so the extraction is a
    // top-level extension on this class instead of a new member — see closeOpenBlocks/emitReplayedReasoning).
    private suspend fun onItemDone(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject
        // tool_search_call has no open wire block and must not touch hasToolUse/turnToolIds — a
        // synthetic/foreign id there would mis-key the reasoning cache (a known prior bug class).
        if (item != null && strOrEmpty(item["type"]) == TOOL_SEARCH_CALL) {
            captureToolSearch(this, item)
            return
        }
        val oi = intOr(evt[OUTPUT_INDEX]) ?: intOr(item?.get("index"))
        // Some backends only attach readable reasoning on the completed item (no per-token
        // summary deltas). Surface that text NOW so Claude Code's thinking UI fills live,
        // not only via the end-of-turn harvest fallback.
        maybeEmitLateReasoning(item, oi, sink)
        closeOpenBlocks(oi, sink)
        if (item == null) return
        emitReplayedReasoning(item, sink)
    }

    private suspend fun maybeEmitLateReasoning(item: JsonObject?, oi: Int?, sink: WireSink) {
        if (item == null || oi == null) return
        if (strOrEmpty(item["type"]) != "reasoning") return
        emitReasoningItemText(item, oi, sink)
    }

    /**
     * If the completed reasoning item carries human-readable text we have not already streamed
     * as deltas, open/append a thinking block with it. Prefer free-form content fields, then
     * structured summary parts (see [reasoningReadableText]).
     */
    private suspend fun emitReasoningItemText(item: JsonObject, outputIndex: Int, sink: WireSink) {
        val raw = reasoningReadableText(item)
        val existing = blocks[reasoningKey(outputIndex)]
        // Already streamed via deltas — don't double-emit (per-ITEM key dedup, not substring —
        // a distinct item whose text substring-matched an earlier one was dropped, audit 2026-07-18).
        val streamedByDeltas = existing != null && existing.sawDelta
        if (raw.isEmpty() || streamedByDeltas) return
        if (!emittedReasoningKeys.add(reasoningKey(outputIndex))) return
        // sequential_cutoff recap arrives through THIS path too (completed items restate prior
        // parts) — same ordered recap model as the delta path, at part granularity. MUST run AFTER
        // every early-return above: the backend can deliver item.done BEFORE the item's remaining
        // deltas (openai/codex#16801 ordering anomaly), and filtering first would record parts whose
        // deltas hadn't arrived — suppressing them both late (sawDelta return) and live (recap
        // match): total summary starvation (found live 2026-07-19).
        // Late-path recap filter: drop the leading recap run + within-item repeats, keep the rest.
        val text = if (ctx.dedupeRepeatedSummaryParts && raw.length <= MAX_DEDUP_SPLIT_CHARS) {
            raw.split(PART_SEPARATOR).filter { part -> !summaryDedup.suppress(outputIndex, part) }
                .joinToString(PART_SEPARATOR)
        } else {
            // WIRE-4: splitting an unbounded upstream reasoning delta allocates one substring per
            // separator without limit; beyond the cap dedup is immaterial, so pass it through unsplit.
            raw
        }
        if (text.isEmpty()) return
        appendLateReasoning(existing, outputIndex, text, sink)
    }

    /** Open (or reuse) the item's thinking block and append [text] with a paragraph separator. */
    private suspend fun appendLateReasoning(existing: BlockState?, outputIndex: Int, text: String, sink: WireSink) {
        val b = existing ?: run {
            if (thinkingBuf.isNotEmpty() && !thinkingBuf.endsWith("\n")) thinkingBuf.append("\n\n")
            val idx = sink.openThinking()
            emittedThinking = true // CX-09: reached the sink, so the client received it
            BlockState(idx, sawDelta = false).also { blocks[reasoningKey(outputIndex)] = it }
        }
        if (thinkingBuf.isNotEmpty() && !thinkingBuf.endsWith("\n")) {
            thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
        thinkingBuf.append(text)
        sink.thinkingDelta(b.index, text)
        b.sawDelta = true
    }

    private suspend fun onSummaryPartAdded(evt: JsonObject, sink: WireSink) {
        // New summary part = new paragraph in the SAME thinking block (v24: closing per part
        // truncated multi-part summaries — protocol violation, deltas after content_block_stop).
        val b = blocks[reasoningKey(intOr(evt[OUTPUT_INDEX]) ?: 0)]
        if (b != null && b.sawDelta) {
            thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
    }

    private suspend fun onThinkingDelta(evt: JsonObject, sink: WireSink) {
        val delta = strOrEmpty(evt[DELTA])
        if (delta.isEmpty()) return
        // sequential_cutoff: whole parts arrive as single deltas; drop a delta that is either the
        // continuation of this item's leading cross-item recap or an exact within-item repeat.
        if (summaryDedup.suppress(intOr(evt[OUTPUT_INDEX]) ?: 0, delta)) return
        val b = ensureThinkingBlock(evt, sink)
        b.sawDelta = true
        thinkingBuf.append(delta)
        sink.thinkingDelta(b.index, delta)
    }

    private suspend fun ensureThinkingBlock(evt: JsonObject, sink: WireSink): BlockState {
        val key = reasoningKey(intOr(evt[OUTPUT_INDEX]) ?: 0)
        blocks[key]?.let { return it }
        // separate multiple reasoning ITEMS in the mirror buffer
        if (thinkingBuf.isNotEmpty() && !thinkingBuf.endsWith("\n")) thinkingBuf.append("\n\n")
        val idx = sink.openThinking()
        emittedThinking = true // CX-09: reached the sink, so the client received it
        val state = BlockState(idx, sawDelta = false)
        blocks[key] = state
        return state
    }

    private suspend fun onTextDelta(evt: JsonObject, sink: WireSink) {
        val delta = strOrEmpty(evt[DELTA])
        if (delta.isEmpty()) return
        val key = intOr(evt[OUTPUT_INDEX]) ?: 0
        val b = blocks[key] ?: BlockState(sink.openText(), sawDelta = false).also { blocks[key] = it }
        emittedText = true
        textBuf.append(delta)
        sink.textDelta(b.index, delta)
    }

    // tool args stream as input_json_delta on the SAME wire block index; the .done frame closes it.
    // When the backend sends complete args only on .done (no .delta frames — valid for small tools),
    // harvest `arguments` once before close so the client does not get tool_use with empty input {}.
    private suspend fun onArgs(evt: JsonObject, sink: WireSink) {
        val oi = intOr(evt[OUTPUT_INDEX]) ?: return
        val b = blocks[oi] ?: return
        if (strOrEmpty(evt["type"]) == "response.function_call_arguments.done") {
            if (!b.sawDelta) emitToolArgText(b, strOrEmpty(evt["arguments"]), sink)
            // CX-01: a backend can truncate arguments mid-string and STILL send .done — the block
            // would close as a Success carrying corrupt tool JSON that Claude Code then parses or
            // dispatches. Validate the accumulated buffer; an opened tool with zero args is equally
            // a malformed tool_use ({} for a tool that needed arguments). Latched, not thrown.
            if (toolArgsInvalid == null) toolArgsInvalid = invalidToolArgsReason(b.args.toString())
            sink.closeBlock(b.index)
            toolSalvage.closedClean(oi)
        } else {
            emitToolArgText(b, strOrEmpty(evt[DELTA]), sink)
        }
    }

    private companion object {
        const val OUTPUT_INDEX = "output_index"
        const val DELTA = "delta"
        const val INCOMPLETE_REASON_MAX_TOKENS = "max_output_tokens"
        const val TOOL_SEARCH_CALL = "tool_search_call"

        // WIRE-4 guard (hardening r1): far above any legitimate single reasoning item's text.
        // REASONING_KEY_BASE/reasoningKey moved to file top-level on main (detekt extraction);
        // only this guard comes across from the hardening branch.
        const val MAX_DEDUP_SPLIT_CHARS = 5_000_000
    }
}

// Leave the positive int space for message/tool output_index; reasoning lives above. Top-level
// (not the reducer's companion, detekt 2026-07-24): onItemDone's extraction below needs it
// unqualified from outside the class, and a private-companion member is invisible there.
private const val REASONING_KEY_BASE = 1_000_000

private fun reasoningKey(outputIndex: Int): Int = REASONING_KEY_BASE + outputIndex

/** onItemDone's block-closing half (extraction, detekt 2026-07-24): close both the tool/message
 *  block and any reasoning block at this output_index, and clear the salvage-open marker. A
 *  top-level extension, not a reducer member — the class is already at TooManyFunctions' ceiling. */
private suspend fun ResponsesEventReducer.closeOpenBlocks(oi: Int?, sink: WireSink) {
    if (oi == null) return
    blocks[oi]?.let { sink.closeBlock(it.index) }
    blocks[reasoningKey(oi)]?.let { sink.closeBlock(it.index) }
    toolSalvage.closedClean(oi)
}

/** onItemDone's replay half (extraction, detekt 2026-07-24): emit the encrypted reasoning IN
 *  POSITION (gated) and collect its envelope for fold/re-anchor replay — see onItemDone's own
 *  comment for why this rides right after the summary closes. */
private suspend fun ResponsesEventReducer.emitReplayedReasoning(item: JsonObject, sink: WireSink) {
    val envelope = ctx.encodeReasoningEnvelope(item) ?: return
    if (shouldEmitReasoning(ctx, item)) sink.addRedactedThinking(envelope)
    if (ctx.collectReasoningEnvelopes) reasoningEnvelopes.add(envelope)
}

/** W4-A: fold a refusal fragment into the reducer's latch, from any of THREE carriers.
 *
 *  Carrier selection lives here, not at the call sites: `response.refusal.delta` puts the text in
 *  `delta`, while `response.refusal.done` and a `refusal`-typed content part both put a WHOLE copy
 *  in `refusal`. The presence of a `delta` key is the discriminator, so one two-label `when` arm in
 *  onStreamEvent and the harvest walk all route through this one function (onStreamEvent sits at
 *  cyclomatic 9 of 10 — a second arm, or an elvis at the call site, fails detekt).
 *
 *  TWO guards, closing two different axes; neither replaces the other.
 *  · TYPE — [strIfString], not [strOrEmpty]. OpenAI types ResponseOutputRefusal.refusal and
 *    ResponseRefusalDoneEvent.refusal as `string`, so a vendor shipping a boolean/number FLAG is
 *    deviating; strOrEmpty would return the non-blank "false" / "0" and fail every working turn of
 *    that vendor as a provider-reported refusal, inverting G20 onto a backend that denied refusing.
 *  · BLANK — an empty `refusal` field must never trip the gate.
 *
 *  DELTA-WINS accumulation, NOT a prefix/substring dedup. Deltas own the buffer and are appended
 *  VERBATIM; a whole-copy carrier is used only when no delta arrived. Running a whole-message dedup
 *  over the INCREMENTAL carrier deleted every already-seen token, so a token-streamed refusal
 *  shipped garbled ("I won't do that. I won't do anything illegal." → "I won't do that. I anything
 *  illegal") — and carrying the model's own words is the whole point of this gate. The one
 *  condition also keeps the overlapping carriers from double-appending. Capped by the NF-06 buffer
 *  law. Top-level extension — the reducer is at its function ceiling. */
private fun ResponsesEventReducer.addRefusal(obj: JsonObject) {
    val isDelta = obj["delta"] != null
    val text = strIfString(if (isDelta) obj["delta"] else obj["refusal"])
    // Round-2 review: same category error as chat's carrier — isBlank() is a whole-value predicate
    // applied per-frame, which deleted whitespace-only refusal fragments and garbled the verdict.
    if (text.isEmpty()) return
    if (!isDelta && text.isBlank()) return
    if (!isDelta && refusalBuf.isNotBlank()) return
    if (refusalBuf.length < BufferCapacity.MAX_BUFFERED_CHARS) refusalBuf.append(text)
}

/** Gated encrypted-reasoning EMISSION predicate — kept out of the handler so its condition stays flat. */
private fun shouldEmitReasoning(ctx: StreamTurnContext, item: JsonObject): Boolean =
    ctx.emitEncryptedReasoning.v && !ctx.compact &&
        strOrEmpty(item["type"]) == "reasoning" && strOrEmpty(item["encrypted_content"]).isNotEmpty()

/**
 * The terminal object's text replaces the streamed buffer when the stream produced nothing, or
 * when it produced only weak "no model text returned" filler that the harvested text improves on.
 */
private fun shouldPreferHarvestedText(current: CharSequence, harvested: String): Boolean {
    if (harvested.isEmpty()) return false
    return current.isEmpty() || (isWeakSummaryText(current.toString()) && !isWeakSummaryText(harvested))
}

private fun intOr(el: JsonElement?): Int? = el.str()?.toIntOrNull()

/** Tool-block salvage ledger for mid-stream re-anchoring: tracks blocks still OPEN at a tear.
 *  A sweep-close of an open tool block committed PARTIAL args JSON — the poison tear that forbids
 *  continuation. NB: cleanly-closed tools do NOT re-enable continuation — a committed
 *  function_call without its function_call_output cannot ride a continuation input (400) and a
 *  re-emitted call would double-dispatch; eligibility refuses ANY tool use (hasToolUse). */
internal class ToolSalvage {
    private val open = HashSet<Int>()

    val tearOpen: Boolean get() = open.isNotEmpty()

    fun opened(oi: Int) {
        open.add(oi)
    }

    fun closedClean(oi: Int) {
        open.remove(oi)
    }
}

/** Shared usage harvest for terminal AND failure payloads (file-level: the reducer sits at its
 *  TooManyFunctions budget). Guarded >0 so a later, richer payload never zeroes an earlier one. */
private fun accumulateUsage(reducer: ResponsesEventReducer, resp: JsonObject) {
    val u = usageFrom(resp)
    if (u.inputTokens > 0) reducer.inputTokens = u.inputTokens
    if (u.outputTokens > 0) reducer.outputTokens = u.outputTokens
    if (u.cachedTokens > 0) reducer.cachedTokens = u.cachedTokens
    if (u.reasoningTokens > 0) reducer.reasoningTokens = u.reasoningTokens
}

/** tool_search_call capture (file-level: the reducer sits at its TooManyFunctions budget, the
 *  same accumulateUsage precedent). Deliberately does NOT touch hasToolUse/blocks/toolSalvage/
 *  turnToolIds — a search call opens no wire block and is not a tool dispatch. */
private fun captureToolSearch(reducer: ResponsesEventReducer, item: JsonObject) {
    parseToolSearchCall(item)?.let { reducer.toolSearches.add(it) }
}

/** Parses one tool_search_call item into a [ToolSearchCall], or null when it should be skipped
 *  (server-executed — execution:"server" means the backend already answered, protocol/src/
 *  models.rs:3693-3728 — or carries no call_id). The ONE parser shared by the live capture above
 *  and the terminal-object harvest fallback ([harvestToolSearchCalls] in Harvested.kt) — never two
 *  hand-rolled readers of the same shape (the v29 copies-drift law). */
internal fun parseToolSearchCall(item: JsonObject): ToolSearchCall? {
    val execution = strOrEmpty(item["execution"])
    if (execution.isNotEmpty() && execution != TOOL_SEARCH_EXECUTION_CLIENT) return null
    // NB: no `id` fallback here (unlike function_call's onItemAdded) — call.raw is echoed
    // VERBATIM into the continuation, so a synthesized id would produce a tool_search_output
    // keyed by a value the echoed call itself doesn't carry: an unpairable item the backend
    // 400s (review 2026-07-24). Skipping is the spec behavior and matches codex-rs's own
    // catch-all (a client-execution call always carries call_id; this is defensive-only).
    val callId = strOrEmpty(item["call_id"])
    if (callId.isEmpty()) return null
    val (query, limit) = parseToolSearchArguments(item["arguments"])
    return ToolSearchCall(callId = ToolSearchCallId(callId), query = query, limit = limit, raw = item)
}

/** `arguments` is a real JSON object on this item type (unlike function_call, where it is a
 *  string) — a string is accepted defensively and parsed. Unparseable/absent arguments yield
 *  query="" (answered exhaustively downstream) rather than dropping the call. */
private fun parseToolSearchArguments(arguments: JsonElement?): Pair<String, Int?> {
    val obj = when (arguments) {
        is JsonObject -> arguments
        is JsonPrimitive -> parseArgumentsString(strOrEmpty(arguments))
        else -> null
    } ?: return "" to null
    return strOrEmpty(obj["query"]) to intOr(obj["limit"])
}

private fun parseArgumentsString(text: String): JsonObject? {
    if (text.isEmpty()) return null
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (ignored: SerializationException) {
        // malformed tool_search_call arguments: fall through to query="" (answered exhaustively
        // downstream), matching this file's own precedent at driveTurn's catch list above.
        null
    }
}

private const val TOOL_SEARCH_EXECUTION_CLIENT = "client"

// NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source, three dialects).
private const val RUNAWAY_GUARD_MESSAGE = "ChatGPT backend: response exceeded max buffered size — aborting"

/** CX-01: null when [text] is valid non-empty tool-argument JSON, else the reason. Top-level —
 *  off ResponsesEventReducer's function budget. */
// CX-01: stream a tool-arg chunk to the wire AND accumulate it (bounded) for the terminal parse.
// Top-level suspend — off ResponsesEventReducer's function budget.
private suspend fun emitToolArgText(b: BlockState, text: String, sink: WireSink) {
    if (text.isEmpty()) return
    sink.inputJsonDelta(b.index, text)
    b.sawDelta = true
    if (b.args.length < BufferCapacity.MAX_BUFFERED_CHARS) b.args.append(text)
}

private fun invalidToolArgsReason(text: String): String? {
    if (text.isBlank()) return "empty arguments for an opened tool call"
    return try {
        Json.parseToJsonElement(text)
        null
    } catch (ignored: SerializationException) {
        "malformed JSON"
    } catch (ignored: IllegalArgumentException) {
        "malformed JSON"
    }
}
