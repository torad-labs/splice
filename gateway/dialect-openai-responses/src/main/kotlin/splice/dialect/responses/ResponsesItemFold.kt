// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the output_item / text /
// tool-args family — everything that opens and closes a wire block.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.BufferCapacity
import splice.spi.WireSink

internal class ResponsesItemFold(
    private val state: ResponsesTurnState,
    private val reasoningFold: ResponsesReasoningFold,
    private val replay: ResponsesReasoningReplay,
) {

    private val frames = ResponsesFrameParse()
    private var toolSynthCounter = 0

    suspend fun onItemAdded(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject ?: return
        val oi = frames.intOr(evt[OUTPUT_INDEX]) ?: frames.intOr(item["index"]) ?: state.blocks.size
        if (JsonScalars.strOrEmpty(item["type"]) == "function_call") {
            // A JsonNull call_id/name must not leak onto the wire as the literal string "null" —
            // strOrEmpty keeps both filtered so the empty-fallback chain below still triggers
            // (review 2026-07-22 round 3).
            val rawId = JsonScalars.strOrEmpty(item["call_id"]).ifEmpty { JsonScalars.strOrEmpty(item["id"]) }
            val id = rawId.ifEmpty { "toolu_synth_${toolSynthCounter++}_$oi" }
            if (rawId.isNotEmpty()) state.turnToolIds.add(rawId)
            val idx = sink.openTool(id = id, name = JsonScalars.strOrEmpty(item["name"]))
            state.blocks[oi] = BlockState(idx, sawDelta = false)
            state.hasToolUse = true
            state.toolSalvage.opened(oi)
        }
        // reasoning + message (text) blocks open lazily on their first delta —
        // avoids empty thinking widgets when a reasoning item carries no summary
    }

    // Split into onItemDone + 2 collaborator operations (detekt 2026-07-24: CyclomaticComplexMethod
    // 11 -> the class already sits at TooManyFunctions' ceiling, so the extraction cannot be a new
    // member — see closeOpenBlocks / ResponsesReasoningReplay.emitReplayedReasoning).
    suspend fun onItemDone(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject
        // tool_search_call has no open wire block and must not touch hasToolUse/turnToolIds — a
        // synthetic/foreign id there would mis-key the reasoning cache (a known prior bug class).
        if (item != null && JsonScalars.strOrEmpty(item["type"]) == TOOL_SEARCH_CALL) {
            captureToolSearch(item)
            return
        }
        val oi = frames.intOr(evt[OUTPUT_INDEX]) ?: frames.intOr(item?.get("index"))
        // Some backends only attach readable reasoning on the completed item (no per-token
        // summary deltas). Surface that text NOW so Claude Code's thinking UI fills live,
        // not only via the end-of-turn harvest fallback.
        reasoningFold.maybeEmitLateReasoning(item, oi, sink)
        closeOpenBlocks(oi, sink)
        if (item == null) return
        replay.emitReplayedReasoning(item, sink)
    }

    /** onItemDone's block-closing half: close both the tool/message block and any reasoning block at
     *  this output_index, and clear the salvage-open marker. */
    suspend fun closeOpenBlocks(oi: Int?, sink: WireSink) {
        if (oi == null) return
        state.blocks[oi]?.let { sink.closeBlock(it.index) }
        state.blocks[frames.reasoningKey(oi)]?.let { sink.closeBlock(it.index) }
        state.toolSalvage.closedClean(oi)
    }

    /** tool_search_call capture. Deliberately does NOT touch hasToolUse/blocks/toolSalvage/
     *  turnToolIds — a search call opens no wire block and is not a tool dispatch. */
    fun captureToolSearch(item: JsonObject) {
        ResponsesToolSearchParse().parseToolSearchCall(item)?.let { state.toolSearches.add(it) }
    }

    suspend fun onTextDelta(evt: JsonObject, sink: WireSink) {
        val delta = JsonScalars.strOrEmpty(evt[DELTA])
        if (delta.isEmpty()) return
        val key = frames.intOr(evt[OUTPUT_INDEX]) ?: 0
        val b = state.blocks[key] ?: BlockState(sink.openText(), sawDelta = false).also { state.blocks[key] = it }
        state.emittedText = true
        state.textBuf.append(delta)
        sink.textDelta(b.index, delta)
    }

    // tool args stream as input_json_delta on the SAME wire block index; the .done frame closes it.
    // When the backend sends complete args only on .done (no .delta frames — valid for small tools),
    // harvest `arguments` once before close so the client does not get tool_use with empty input {}.
    suspend fun onArgs(evt: JsonObject, sink: WireSink) {
        val oi = frames.intOr(evt[OUTPUT_INDEX]) ?: return
        val b = state.blocks[oi] ?: return
        if (JsonScalars.strOrEmpty(evt["type"]) == "response.function_call_arguments.done") {
            if (!b.sawDelta) emitToolArgText(b, JsonScalars.strOrEmpty(evt["arguments"]), sink)
            // CX-01: a backend can truncate arguments mid-string and STILL send .done — the block
            // would close as a Success carrying corrupt tool JSON that Claude Code then parses or
            // dispatches. Validate the accumulated buffer; an opened tool with zero args is equally
            // a malformed tool_use ({} for a tool that needed arguments). Latched, not thrown.
            if (state.toolArgsInvalid == null) state.toolArgsInvalid = frames.invalidToolArgsReason(b.args.toString())
            sink.closeBlock(b.index)
            state.toolSalvage.closedClean(oi)
        } else {
            emitToolArgText(b, JsonScalars.strOrEmpty(evt[DELTA]), sink)
        }
    }

    // CX-01: stream a tool-arg chunk to the wire AND accumulate it (bounded) for the terminal parse.
    private suspend fun emitToolArgText(b: BlockState, text: String, sink: WireSink) {
        if (text.isEmpty()) return
        sink.inputJsonDelta(b.index, text)
        b.sawDelta = true
        if (b.args.length < BufferCapacity.MAX_BUFFERED_CHARS) b.args.append(text)
    }
}
