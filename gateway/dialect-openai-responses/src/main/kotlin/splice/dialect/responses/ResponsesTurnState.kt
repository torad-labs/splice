// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: every per-turn mutable
// field the reducer and its folds accumulate into, plus the refusal latch (addRefusal) — the one
// operation with call sites on both sides of the event-stream/terminal-backfill split.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.turn.ToolSearchCall
import splice.core.util.JsonScalars
import splice.spi.BufferCapacity
import splice.spi.ClassifiedFailure

/**
 * Folds the upstream SSE event stream into per-turn buffers/flags. One shared mutable ledger read
 * and written by [ResponsesEventReducer] and its collaborating folds — the translator reads it
 * after the loop ends to render the terminal outcome.
 */
internal class ResponsesTurnState {

    // Int keys: message/tool blocks use the upstream output_index directly; reasoning blocks
    // use REASONING_KEY_BASE + output_index so the two families never collide and we never
    // allocate "reasoning:$oi" / oi.toString() strings on the hot path.
    val blocks = HashMap<Int, BlockState>()
    private var retainedToolArgsChars = 0L
    val bufferedToolArgsChars: Long get() = retainedToolArgsChars

    fun putBlock(key: Int, block: BlockState) {
        removeBlock(key)
        blocks[key] = block
    }

    fun bufferToolArgs(block: BlockState, text: String) {
        if (block.args.length >= BufferCapacity.MAX_BUFFERED_CHARS) return
        val before = block.args.length
        block.args.append(text)
        retainedToolArgsChars += (block.args.length - before).toLong()
    }

    fun removeBlock(key: Int): BlockState? = blocks.remove(key)?.also {
        retainedToolArgsChars -= it.args.length.toLong()
    }

    var hasToolUse = false
    var emittedText = false

    /** codex-parity active-item tracking (sequential_cutoff, 2026-08-26; codex-rs
     *  session/turn.rs: `active_item` is set by every OutputItemAdded and taken by
     *  OutputItemDone). A reasoning summary_text.done belonging to any OTHER item is a stale
     *  cutoff restatement and is dropped — the id filter that replaces text-matching dedup. */
    var activeItemId: String? = null
    var activeItemOi: Int? = null

    /** CX-09: a thinking block actually reached the sink. NOT the same as thinkingBuf being
     *  non-empty — [ResponsesTerminalBackfill.harvestFallback] refills that buffer from the
     *  completed response object without emitting anything, and that is precisely the turn the
     *  honesty gate must still call empty. */
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

    // Mid-stream re-anchor salvage (eli 2026-07-24) — see [ToolSalvage].
    val toolSalvage = ToolSalvage()

    // Reasoning-cache capture (RC-1): the round's REAL upstream function_call ids, in order.
    val turnToolIds = mutableListOf<String>()

    // tool_search_call items this round emitted (deferred tool surface) — populated only when the
    // gateway declared deferral this turn; empty otherwise (pre-deferral behaviour intact).
    val toolSearches = mutableListOf<ToolSearchCall>()

    /** W4-A: fold a refusal fragment into this latch, from any of THREE carriers.
     *
     *  Carrier selection lives here, not at the call sites: `response.refusal.delta` puts the text in
     *  `delta`, while `response.refusal.done` and a `refusal`-typed content part both put a WHOLE copy
     *  in `refusal`. The presence of a `delta` key is the discriminator, so one two-label `when` arm in
     *  the event dispatch and the harvest walk both route through this one function.
     *
     *  TWO guards, closing two different axes; neither replaces the other.
     *  · TYPE — [JsonScalars.strIfString], not [JsonScalars.strOrEmpty]. OpenAI types
     *    ResponseOutputRefusal.refusal and ResponseRefusalDoneEvent.refusal as `string`, so a vendor
     *    shipping a boolean/number FLAG is deviating; strOrEmpty would return the non-blank "false" /
     *    "0" and fail every working turn of that vendor as a provider-reported refusal, inverting G20
     *    onto a backend that denied refusing.
     *  · BLANK — an empty `refusal` field must never trip the gate.
     *
     *  DELTA-WINS accumulation, NOT a prefix/substring dedup. Deltas own the buffer and are appended
     *  VERBATIM; a whole-copy carrier is used only when no delta arrived. Running a whole-message dedup
     *  over the INCREMENTAL carrier deleted every already-seen token, so a token-streamed refusal
     *  shipped garbled ("I won't do that. I won't do anything illegal." → "I won't do that. I anything
     *  illegal") — and carrying the model's own words is the whole point of this gate. The one
     *  condition also keeps the overlapping carriers from double-appending. Capped by the NF-06 buffer
     *  law. */
    fun addRefusal(obj: JsonObject) {
        val isDelta = obj["delta"] != null
        val text = JsonScalars.strIfString(if (isDelta) obj["delta"] else obj["refusal"])
        // Round-2 review: same category error as chat's carrier — isBlank() is a whole-value predicate
        // applied per-frame, which deleted whitespace-only refusal fragments and garbled the verdict.
        if (text.isEmpty()) return
        if (!isDelta && text.isBlank()) return
        if (!isDelta && refusalBuf.isNotBlank()) return
        if (refusalBuf.length < BufferCapacity.MAX_BUFFERED_CHARS) refusalBuf.append(text)
    }
}
