package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.reasoning.ReasoningHistory
import splice.core.util.JsonScalars
import splice.core.wire.ToolUseBlock
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.upstream.ToolNameShortener

/** Completed reasoning is server history, independent of readable reasoning and terminal accounting.
 * No encrypted envelope is a client content block. Missing readable model text stays missing. */
internal class ResponsesReasoningReplay(
    private val ctx: StreamTurnContext,
    private val state: ResponsesTurnState,
    private val names: ToolNameShortener,
) {
    private val cursor = ctx.reasoningCapture.history
    private val initial = cursor?.copy()
    private val captured = linkedMapOf<String, String>()
    private val streamedCalls = hashSetOf<String>()
    private var roundId: String? = null
    private var streamedItems = 0

    init {
        if (ctx.reasoningCapture.collectEnvelopes) state.nativePrefix = ResponsesNativePrefix()
    }

    /** Advance in wire order, including prose whose item never completes before a recovery. */
    fun textDelta(index: Int, text: String) {
        cursor?.text("assistant", text)
        state.nativePrefix?.delta(index, text)
    }

    fun collect(item: JsonObject, outputIndex: Int?) {
        val index = outputIndex ?: streamedItems
        state.nativePrefix?.collect(item, index)
        collectAt(item, cursor, outputIndex = index, terminal = false)
        streamedItems++
    }

    /** Terminal-only backends carry the same ordered output array. Revisit it from the request cursor;
     * already captured ids are skipped, and streamed positions never depend on the terminal arriving. */
    fun finish() {
        val output = state.finalResponse?.get("output") as? JsonArray ?: return
        state.nativePrefix?.finish(output)
        if (output.isEmpty()) return
        val at = initial?.copy()
        val conflict = deliveredOrderConflicts(output)
        val bounds = TerminalHistoryBounds(output, initial, cursor, streamedCalls)
        output.forEachIndexed { index, value ->
            (value as? JsonObject)?.let {
                if (conflict) {
                    if (JsonScalars.str(it, "type") == "reasoning") capture(it, bounds.at(index), index, true)
                } else {
                    collectAt(it, at, outputIndex = index, terminal = true)
                }
            }
        }
        if (!conflict) publishCursor(at)
        reconcileEnvelopes(output)
    }

    private fun deliveredOrderConflicts(output: JsonArray): Boolean {
        if (!state.emittedText) return false
        val terminalText = output.mapNotNull { it as? JsonObject }
            .filter { JsonScalars.str(it, "type") == "message" }.joinToString("", transform = ::messageText)
        return terminalText != state.textBuf.toString()
    }

    private fun collectAt(
        item: JsonObject,
        at: ReasoningHistory?,
        outputIndex: Int,
        terminal: Boolean,
    ) {
        when (JsonScalars.str(item, "type")) {
            "reasoning" -> capture(item, at, outputIndex, terminal)
            "message" -> if (terminal) at?.text("assistant", messageText(item))
            "function_call" -> advanceTool(item, at)
        }
    }

    private fun capture(item: JsonObject, at: ReasoningHistory?, outputIndex: Int, terminal: Boolean) {
        val id = JsonScalars.str(item, "id") ?: return
        val envelope = ctx.encodeReasoningEnvelope(item) ?: return
        val fresh = rememberEnvelope(id, envelope)
        if (!fresh && !terminal) return
        val round = roundId ?: id.also { roundId = it }
        at?.let {
            ctx.reasoningCapture.onTurn.positioned(
                it.key,
                envelope,
                ReasoningItemOrder(round, outputIndex, terminal, id),
            )
        }
    }

    private fun rememberEnvelope(id: String, envelope: String): Boolean {
        val fresh = captured.putIfAbsent(id, envelope) == null
        if (fresh && ctx.reasoningCapture.collectEnvelopes) state.reasoningEnvelopes.add(envelope)
        return fresh
    }

    private fun advanceTool(item: JsonObject, at: ReasoningHistory?) {
        val id = JsonScalars.str(item, "call_id") ?: return
        val arguments = JsonScalars.str(item, "arguments") ?: return
        val input = JsonScalars.objectOrNull(Json, arguments) ?: return
        val name = names.restore(JsonScalars.str(item, "name").orEmpty())
        if (at != null) {
            ResponsesHistoryCursor(at).append("assistant", ToolUseBlock(id, name, input))
            if (at === cursor) streamedCalls.add(id)
        }
    }

    private fun reconcileEnvelopes(output: JsonArray) {
        if (!ctx.reasoningCapture.collectEnvelopes) return
        val ids = output.mapNotNull { JsonScalars.str(it as? JsonObject, "id") }.filter { it in captured }.toSet()
        val ordered = ids.mapNotNull { captured[it] } + captured.filterKeys { it !in ids }.values
        state.reasoningEnvelopes.clear()
        state.reasoningEnvelopes.addAll(ordered)
    }

    private fun publishCursor(at: ReasoningHistory?) {
        val position = at ?: return
        val current = cursor ?: return
        // A sparse final output can add missing items, but cannot rewind visible items already streamed.
        if (position.offset >= current.offset) current.restore(position)
    }

    private fun messageText(item: JsonObject): String = (item["content"] as? JsonArray).orEmpty().joinToString("") {
        JsonScalars.str(it as? JsonObject, "text").orEmpty()
    }
}

/** A conflicting terminal proves the outer boundaries, but cannot place unseen interior reasoning. */
private class TerminalHistoryBounds(
    output: JsonArray,
    private val before: ReasoningHistory?,
    private val after: ReasoningHistory?,
    streamedCalls: Set<String>,
) {
    private val context = output.mapIndexedNotNull { index, value ->
        val item = value as? JsonObject
        when (JsonScalars.str(item, "type")) {
            "message", "function_call" -> IndexedValue(index, checkNotNull(item))
            else -> null
        }
    }
    private val first = context.firstOrNull()?.index
    private val last = context.lastOrNull()?.index
    private val callsObserved = context.filter { JsonScalars.str(it.value, "type") == "function_call" }
        .all {
            val id = JsonScalars.str(it.value, "call_id")
            id != null && id in streamedCalls
        }

    fun at(index: Int): ReasoningHistory? = when {
        first == null -> after
        index < first -> before
        !callsObserved -> null
        last == null -> null
        index > last -> after
        else -> null
    }
}
