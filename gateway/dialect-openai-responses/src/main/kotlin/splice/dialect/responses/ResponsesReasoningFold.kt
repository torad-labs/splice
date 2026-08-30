// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: rendering human-readable
// reasoning into thinking blocks — the live-delta path and the late/completed-item path together,
// deliberately, since both call sites must see ONE SummaryDedup instance (the 2026-07-20/07-26
// mirror-duplication invariant this file makes structurally unreachable rather than merely avoided).
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.turn.SharedSummaryParts
import splice.core.util.JsonScalars
import splice.spi.WireSink

private const val PART_SEPARATOR = "\n\n"

// WIRE-4 guard (hardening r1): far above any legitimate single reasoning item's text.
private const val MAX_DEDUP_SPLIT_CHARS = 5_000_000

internal class ResponsesReasoningFold(
    private val ctx: StreamTurnContext,
    private val state: ResponsesTurnState,
    summaryParts: SharedSummaryParts,
) {

    private val frames = ResponsesFrameParse()
    private val harvest = ResponsesHarvest()

    // Late-reasoning items already emitted, keyed by their reasoning block index. Substring
    // dedup on thinkingBuf dropped a DISTINCT item whose text happened to be a substring of an
    // earlier one, diverging wire from mirror (audit 2026-07-18); track per item instead.
    private val emittedReasoningKeys = HashSet<Int>()

    // sequential_cutoff restatement dedup — state + decision encapsulated in SummaryDedup.
    private val summaryDedup = SummaryDedup(ctx.dedupeRepeatedSummaryParts, summaryParts)

    // sequential_cutoff mode (codex-rs parity, ported verbatim from session/turn.rs 2026-08-26):
    // the backend streams MULTIPLE reasoning items CONCURRENTLY, and each item's summary stream
    // restates the running summary (same text, re-fired under original AND new item ids). codex
    // IGNORES summary deltas and part.added entirely in this mode and renders ONLY
    // reasoning_summary_text.done events belonging to the ACTIVE item — pure id filtering, no
    // text comparison. ctx.dedupeRepeatedSummaryParts is true exactly when the request asked for
    // sequential_cutoff delivery, so it doubles as the mode flag.

    /** The reducer's single reasoning-family arm: dispatch by event type. */
    suspend fun onReasoningEvent(evt: JsonObject, sink: WireSink) {
        when (JsonScalars.strOrEmpty(evt["type"])) {
            "response.reasoning_summary_part.added" -> onSummaryPartAdded(evt, sink)
            "response.reasoning_summary_text.done" -> onSummaryTextDone(evt, sink)
            else -> onThinkingDelta(evt, sink)
        }
    }

    suspend fun onSummaryPartAdded(evt: JsonObject, sink: WireSink) {
        // cutoff mode: part boundaries are rendered by the done path, never from part.added.
        if (ctx.dedupeRepeatedSummaryParts) return
        // New summary part = new paragraph in the SAME thinking block (v24: closing per part
        // truncated multi-part summaries — protocol violation, deltas after content_block_stop).
        val b = state.blocks[frames.reasoningKey(frames.intOr(evt[OUTPUT_INDEX]) ?: 0)]
        if (b != null && b.sawDelta) {
            state.thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
    }

    suspend fun onThinkingDelta(evt: JsonObject, sink: WireSink) {
        val delta = JsonScalars.strOrEmpty(evt[DELTA])
        if (delta.isEmpty()) return
        // cutoff mode: summary deltas are NOISE (concurrent items interleave and restate; codex
        // `continue`s on ReasoningSummaryDelta). Raw reasoning_text deltas still stream live.
        if (ctx.dedupeRepeatedSummaryParts &&
            JsonScalars.strOrEmpty(evt["type"]) == "response.reasoning_summary_text.delta"
        ) {
            return
        }
        if (summaryDedup.suppress(frames.intOr(evt[OUTPUT_INDEX]) ?: 0, delta)) return
        val b = ensureThinkingBlock(evt, sink)
        b.sawDelta = true
        state.thinkingBuf.append(delta)
        sink.thinkingDelta(b.index, delta)
    }

    /** cutoff mode's ONLY summary render path (codex ReasoningSummaryDone arm): the completed
     *  part text, atomically, iff [renderableSummaryDone] admits it. */
    suspend fun onSummaryTextDone(evt: JsonObject, sink: WireSink) {
        val text = JsonScalars.strOrEmpty(evt["text"])
        if (!renderableSummaryDone(evt, text)) return
        val b = ensureThinkingBlock(evt, sink)
        // codex emits a section break for summary_index > 0; block-non-empty is the same boundary
        // without ever leading an empty block with a separator (the first RENDERED part of an
        // item can sit at summary_index > 0 when its restated prefix was dropped).
        if (b.sawDelta) {
            state.thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
        b.sawDelta = true
        state.thinkingBuf.append(text)
        sink.thinkingDelta(b.index, text)
    }

    /** Two filters, one decision. First codex's (session/turn.rs ReasoningSummaryDone arm): the
     *  part must belong to the ACTIVE item — any other item's done is a stale cutoff restatement
     *  (original-id re-fires, or parts of an item that lost the active slot). Id match when both
     *  sides carry one, else output_index (codex matches id; oi is the lite-shape fallback).
     *  Second splice's own: conversation-scoped exact-match dedup. codex tolerates one residual
     *  duplication class (a restated part re-fired under the ACTIVE item's own id — observed live
     *  2026-08-26, item1 si=1 re-delivering item0's part while item1 streamed) and holds no
     *  cross-POST state at all; done-path parts are ATOMIC, so the text match that token-granular
     *  deltas defeated is sound here. Each layer covers the other's blind spot. */
    private fun renderableSummaryDone(evt: JsonObject, text: String): Boolean {
        if (!ctx.dedupeRepeatedSummaryParts || text.isEmpty()) return false
        val itemId = JsonScalars.strOrEmpty(evt["item_id"])
        val oi = frames.intOr(evt[OUTPUT_INDEX])
        val active = if (itemId.isNotEmpty() && state.activeItemId != null) {
            itemId == state.activeItemId
        } else {
            oi != null && oi == state.activeItemOi
        }
        return active && !summaryDedup.suppress(oi ?: 0, text)
    }

    suspend fun ensureThinkingBlock(evt: JsonObject, sink: WireSink): BlockState {
        val key = frames.reasoningKey(frames.intOr(evt[OUTPUT_INDEX]) ?: 0)
        state.blocks[key]?.let { return it }
        // separate multiple reasoning ITEMS in the mirror buffer
        if (state.thinkingBuf.isNotEmpty() && !state.thinkingBuf.endsWith("\n")) state.thinkingBuf.append("\n\n")
        val idx = sink.openThinking()
        state.emittedThinking = true // CX-09: reached the sink, so the client received it
        val blockState = BlockState(idx, sawDelta = false)
        state.blocks[key] = blockState
        return blockState
    }

    suspend fun maybeEmitLateReasoning(item: JsonObject?, oi: Int?, sink: WireSink) {
        if (item == null || oi == null) return
        if (JsonScalars.strOrEmpty(item["type"]) != "reasoning") return
        // cutoff mode: the done-event path is the complete render surface (codex renders nothing
        // from completed items in this mode — a completed item that never went active is a
        // restatement carrier, and re-rendering it is the staircase this port kills).
        if (ctx.dedupeRepeatedSummaryParts) return
        emitReasoningItemText(item, oi, sink)
    }

    /**
     * If the completed reasoning item carries human-readable text we have not already streamed
     * as deltas, open/append a thinking block with it. Prefer free-form content fields, then
     * structured summary parts (see [ResponsesHarvest.reasoningReadableText]).
     */
    private suspend fun emitReasoningItemText(item: JsonObject, outputIndex: Int, sink: WireSink) {
        val raw = harvest.reasoningReadableText(item)
        val existing = state.blocks[frames.reasoningKey(outputIndex)]
        // Already streamed via deltas — don't double-emit (per-ITEM key dedup, not substring —
        // a distinct item whose text substring-matched an earlier one was dropped, audit 2026-07-18).
        val streamedByDeltas = existing != null && existing.sawDelta
        if (raw.isEmpty() || streamedByDeltas) return
        if (!emittedReasoningKeys.add(frames.reasoningKey(outputIndex))) return
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
            if (state.thinkingBuf.isNotEmpty() && !state.thinkingBuf.endsWith("\n")) state.thinkingBuf.append("\n\n")
            val idx = sink.openThinking()
            state.emittedThinking = true // CX-09: reached the sink, so the client received it
            BlockState(idx, sawDelta = false).also { state.blocks[frames.reasoningKey(outputIndex)] = it }
        }
        if (state.thinkingBuf.isNotEmpty() && !state.thinkingBuf.endsWith("\n")) {
            state.thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
        state.thinkingBuf.append(text)
        sink.thinkingDelta(b.index, text)
        b.sawDelta = true
    }
}
