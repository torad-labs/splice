// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the output_item / text /
// tool-args family — everything that opens and closes a wire block.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.WireSink

internal class ResponsesItemFold(
    private val state: ResponsesTurnState,
    private val reasoningFold: ResponsesReasoningFold,
    private val replay: ResponsesReasoningReplay,
) {

    private val frames = ResponsesFrameParse()
    private val harvest = ResponsesHarvest()
    private var toolSynthCounter = 0

    suspend fun onItemAdded(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject ?: return
        state.streamedItemTypes.add(JsonScalars.strOrEmpty(item["type"]).ifEmpty { "?" })
        val oi = frames.intOr(evt[OUTPUT_INDEX]) ?: frames.intOr(item["index"]) ?: state.blocks.size
        // codex parity: EVERY added item (reasoning, message, function_call, search) becomes the
        // active item; summary done-events are rendered only while their item is active.
        state.activeItemId = JsonScalars.strOrEmpty(item["id"]).ifEmpty { null }
        state.activeItemOi = oi
        if (JsonScalars.strOrEmpty(item["type"]) == "function_call") {
            // DR-107: an added at an already-open output_index EVICTED the live block map-only —
            // its wire stayed open (start, no stop) and its args were never validated (no CX-01
            // latch possible), orphaning tool AND text blocks alike on index reuse. Close and
            // validate the occupant through the one sanctioned path before opening the new block.
            if (state.blocks[oi] != null) closeOpenBlocks(oi, sink)
            // A JsonNull call_id/name must not leak onto the wire as the literal string "null" —
            // strOrEmpty keeps both filtered so the empty-fallback chain below still triggers
            // (review 2026-07-22 round 3).
            val rawId = JsonScalars.strOrEmpty(item["call_id"]).ifEmpty { JsonScalars.strOrEmpty(item["id"]) }
            val id = rawId.ifEmpty { "toolu_synth_${toolSynthCounter++}_$oi" }
            if (rawId.isNotEmpty()) state.turnToolIds.add(rawId)
            val idx = sink.openTool(id = id, name = JsonScalars.strOrEmpty(item["name"]))
            state.putBlock(oi, BlockState(idx, sawDelta = false, tool = true))
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
        // codex parity: OutputItemDone takes the active item — a summary done-event arriving
        // between items (or after a later item started) is stale and must not render.
        state.activeItemId = null
        state.activeItemOi = null
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
        maybeHarvestLateToolArgs(item, oi, sink)
        recordDoneDetail(item)
        closeOpenBlocks(oi, sink)
        if (item == null) return
        replay.emitReplayedReasoning(item, sink)
    }

    /** DR-77: a backend may close a function_call via output_item.done ALONE — no arguments.delta,
     *  no arguments.done. Harvest the completed item's `arguments` exactly as onArgs' .done branch
     *  does, so the client is not handed tool_use with empty input; closeOpenBlocks then validates. */
    private suspend fun maybeHarvestLateToolArgs(item: JsonObject?, oi: Int?, sink: WireSink) {
        if (item == null || oi == null) return
        if (JsonScalars.strOrEmpty(item["type"]) != "function_call") return
        val b = state.blocks[oi] ?: return
        // DR-134: the ITEM being a function_call says nothing about the BLOCK at this index. A text
        // block is built with sawDelta=false and only emitToolArgText ever flips it, so `!b.sawDelta`
        // is permanently true for one — without `b.tool` a text delta followed by an item-done
        // function_call at the SAME index harvested args straight into the open text block, and
        // closeOpenBlocks then skipped the CX-01 latch, grading a corrupt wire a clean Success. Third
        // site of the DR-108 law, and the only one reached with no output_item.added — so DR-107's
        // eviction never runs here and cannot cover it.
        if (b.tool && !b.sawDelta) emitToolArgText(b, JsonScalars.strOrEmpty(item["arguments"]), sink)
    }

    /** Evidence for the empty-turn line: what a completed message/reasoning item actually carried,
     *  since these can ride output_item.done with an EMPTY terminal output array. Records e.g.
     *  message(text=0,parts=[]) or reasoning(enc=1842,sum=0) — the string appended to streamedItemTypes
     *  is overwritten with the detailed form. Evidence only. */
    private fun recordDoneDetail(item: JsonObject?) {
        if (item == null) return
        val type = JsonScalars.strOrEmpty(item["type"])
        val detail = when (type) {
            "message" -> {
                val parts = (item["content"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val text = harvest.messageItemText(item).length
                val ptypes = parts.joinToString(",") { JsonScalars.strOrEmpty(it["type"]).ifEmpty { "?" } }
                "message(text=$text,parts=[$ptypes])"
            }
            "reasoning" -> "reasoning(enc=${JsonScalars.strOrEmpty(item["encrypted_content"]).length},sum=${(item["summary"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0})"
            else -> return
        }
        // replace the last matching bare type recorded at added-time with the detailed form
        val idx = state.streamedItemTypes.indexOfLast { it == type }
        if (idx >= 0) state.streamedItemTypes[idx] = detail else state.streamedItemTypes.add(detail)
    }

    /** onItemDone's block-closing half: close both the tool/message block and any reasoning block at
     *  this output_index, and clear the salvage-open marker. */
    suspend fun closeOpenBlocks(oi: Int?, sink: WireSink) {
        if (oi == null) return
        state.removeBlock(oi)?.let { b ->
            // DR-77 (CX-01 completion): output_item.done can close a tool block without ever
            // passing the arguments.done handler — the only site that validated. Corrupt or
            // truncated args must latch on THIS path too, or the turn ends a clean Success
            // dispatching garbage. Same latch, same first-reason-wins.
            if (b.tool && state.toolArgsInvalid == null) {
                state.toolArgsInvalid = frames.invalidToolArgsReason(b.args.toString())
            }
            sink.closeBlock(b.index)
        }
        state.removeBlock(frames.reasoningKey(oi))?.let { sink.closeBlock(it.index) }
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
        val existing = state.blocks[key]
        // DR-108: an output_index does not cross block TYPES — a text delta aimed at an open
        // function_call block would emit text_delta INSIDE tool_use on the wire (protocol-
        // corrupt) and pollute the emittedText/textBuf honesty state. Dropped, the same fate as
        // the passthrough's PT-001 unmapped-index deltas.
        val mistargeted = existing != null && existing.tool
        if (mistargeted) return
        val b = existing ?: BlockState(sink.openText(), sawDelta = false).also { state.blocks[key] = it }
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
        // DR-108 mirror: an args frame aimed at an open TEXT block would emit input_json_delta
        // inside a text block, and the .done shape would even CLOSE it through the args path.
        if (!b.tool) return
        if (JsonScalars.strOrEmpty(evt["type"]) == "response.function_call_arguments.done") {
            if (!b.sawDelta) emitToolArgText(b, JsonScalars.strOrEmpty(evt["arguments"]), sink)
            // CX-01: a backend can truncate arguments mid-string and STILL send .done — the block
            // would close as a Success carrying corrupt tool JSON that Claude Code then parses or
            // dispatches. Validate the accumulated buffer; an opened tool with zero args is equally
            // a malformed tool_use ({} for a tool that needed arguments). Latched, not thrown.
            if (state.toolArgsInvalid == null) state.toolArgsInvalid = frames.invalidToolArgsReason(b.args.toString())
            sink.closeBlock(b.index)
            state.removeBlock(oi)
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
        state.bufferToolArgs(b, text)
    }
}
