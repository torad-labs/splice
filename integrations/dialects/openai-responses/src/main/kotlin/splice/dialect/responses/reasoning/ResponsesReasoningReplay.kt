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
    private var roundId: String? = null
    private var streamedItems = 0

    init {
        if (ctx.reasoningCapture.collectEnvelopes) state.nativePrefix = ResponsesNativePrefix()
    }

    fun collect(item: JsonObject, outputIndex: Int?) {
        val index = outputIndex ?: streamedItems
        val message = state.nativePrefix?.collect(item, index)
        collectAt(item, cursor, outputIndex = index, terminal = false, message = message)
        streamedItems++
    }

    /** Terminal-only backends carry the same ordered output array. Revisit it from the request cursor;
     * already captured ids are skipped, and streamed positions never depend on the terminal arriving. */
    fun finish() {
        val output = state.finalResponse?.get("output") as? JsonArray ?: return
        state.nativePrefix?.finish(output)
        if (output.isEmpty()) return
        val at = initial?.copy()
        output.forEachIndexed { index, value ->
            (value as? JsonObject)?.let {
                collectAt(
                    it,
                    at,
                    outputIndex = index,
                    terminal = true,
                )
            }
        }
        publishCursor(at)
        reconcileEnvelopes(output)
    }

    private fun collectAt(
        item: JsonObject,
        at: ReasoningHistory?,
        outputIndex: Int,
        terminal: Boolean,
        message: String? = null,
    ) {
        when (JsonScalars.str(item, "type")) {
            "reasoning" -> capture(item, at, outputIndex, terminal)
            "message" -> advanceMessage(item, at, message)
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

    private fun advanceMessage(item: JsonObject, at: ReasoningHistory?, message: String?) {
        at?.text("assistant", message ?: messageText(item))
    }

    private fun advanceTool(item: JsonObject, at: ReasoningHistory?) {
        val id = JsonScalars.str(item, "call_id") ?: return
        val arguments = JsonScalars.str(item, "arguments") ?: return
        val input = JsonScalars.objectOrNull(Json, arguments) ?: return
        val name = names.restore(JsonScalars.str(item, "name").orEmpty())
        if (at != null) ResponsesHistoryCursor(at).append("assistant", ToolUseBlock(id, name, input))
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
