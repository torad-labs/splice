// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: rendering human-readable
// reasoning into thinking blocks — the live-delta path and the late/completed-item path together,
// deliberately, since both call sites must see ONE SummaryDedup instance (the 2026-07-20/07-26
// mirror-duplication invariant this file makes structurally unreachable rather than merely avoided).
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

private const val PART_SEPARATOR = "\n\n"

// WIRE-4 guard (hardening r1): far above any legitimate single reasoning item's text.
private const val MAX_DEDUP_SPLIT_CHARS = 5_000_000

internal class ResponsesReasoningFold(private val ctx: StreamTurnContext, private val state: ResponsesTurnState) {

    private val frames = ResponsesFrameParse()
    private val harvest = ResponsesHarvest()

    // Late-reasoning items already emitted, keyed by their reasoning block index. Substring
    // dedup on thinkingBuf dropped a DISTINCT item whose text happened to be a substring of an
    // earlier one, diverging wire from mirror (audit 2026-07-18); track per item instead.
    private val emittedReasoningKeys = HashSet<Int>()

    // sequential_cutoff restatement dedup — state + decision encapsulated in SummaryDedup.
    private val summaryDedup = SummaryDedup(ctx.dedupeRepeatedSummaryParts, ctx.summaryPartsShared)

    suspend fun onSummaryPartAdded(evt: JsonObject, sink: WireSink) {
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
        // sequential_cutoff: whole parts arrive as single deltas; drop a delta that is either the
        // continuation of this item's leading cross-item recap or an exact within-item repeat.
        if (summaryDedup.suppress(frames.intOr(evt[OUTPUT_INDEX]) ?: 0, delta)) return
        val b = ensureThinkingBlock(evt, sink)
        b.sawDelta = true
        state.thinkingBuf.append(delta)
        sink.thinkingDelta(b.index, delta)
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
