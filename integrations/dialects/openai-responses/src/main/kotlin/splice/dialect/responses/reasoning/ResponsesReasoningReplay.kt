package splice.dialect.responses.reasoning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.perf.InputDigest
import splice.core.reasoning.ReasoningHistory
import splice.core.util.JsonScalars
import splice.core.wire.ToolUseBlock
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.upstream.ToolNameShortener

private const val NATIVE_FUNCTION_CALL_TYPE = "function_call"

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
    private var positionsProven = true
    private var terminalBatch: String? = null

    init {
        state.nativePrefix = ResponsesNativePrefix(state.textBuf)
    }

    /** Advance in wire order, including prose whose item never completes before a recovery. */
    fun textDelta(index: Int, text: String) {
        messageTextDelivered(text)
        state.nativePrefix?.delta(index, text)
    }

    /** Completed native messages already contain this suffix; only their delivered cursor advances. */
    fun messageTextDelivered(text: String, index: Int? = null) {
        cursor?.text("assistant", text)
        index?.let {
            state.nativePrefix?.ownership?.representedSuffix(it, text.length)
            state.nativePrefix?.delivered?.text(it, text)
        }
    }

    fun messageUndelivered() {
        positionsProven = false
        state.nativePrefix?.delivered?.invalidate()
    }

    fun collect(item: JsonObject, outputIndex: Int?): IndexedValue<String>? {
        val index = outputIndex ?: streamedItems
        val suffix = state.nativePrefix?.collect(item, index)
        if (JsonScalars.str(item, "type") == NATIVE_MESSAGE_TYPE && suffix == null) messageUndelivered()
        val position = cursor.takeIf { positionsProven && state.nativePrefix?.delivered?.callId == null }
        collectAt(item, position, outputIndex = index, terminal = false)
        if (JsonScalars.str(item, "type") == NATIVE_FUNCTION_CALL_TYPE) state.nativePrefix?.delivered?.item(item, index)
        streamedItems++
        return suffix?.let { IndexedValue(index, it) }
    }

    /** Terminal-only backends carry the same ordered output array. Revisit it from the request cursor;
     * already captured ids are skipped, and streamed positions never depend on the terminal arriving. */
    fun finish() {
        val output = state.finalResponse?.get("output") as? JsonArray ?: return
        state.nativePrefix?.finish(output)
        if (output.isEmpty()) return
        terminalBatch = InputDigest.hex(output)
        val at = initial?.copy()
        val source = terminalSource(output)
        val bounds = TerminalHistoryBounds(output, initial, cursor.takeIf { positionsProven }, streamedCalls)
        output.forEachIndexed { index, value ->
            (value as? JsonObject)?.let {
                collectTerminal(it, at, index, bounds, source)
            }
        }
        if (source == TerminalHistorySource.ARRAY) publishCursor(at)
        reconcileEnvelopes(output)
    }

    private fun terminalSource(output: JsonArray): TerminalHistorySource = when {
        state.nativePrefix?.delivered?.callId != null -> TerminalHistorySource.OWNED
        deliveredOrderConflicts(output) -> TerminalHistorySource.DELIVERED
        else -> TerminalHistorySource.ARRAY
    }

    private fun collectTerminal(
        item: JsonObject,
        at: ReasoningHistory?,
        index: Int,
        bounds: TerminalHistoryBounds,
        source: TerminalHistorySource,
    ) {
        when (source) {
            TerminalHistorySource.OWNED ->
                if (JsonScalars.str(item, "type") == "reasoning") capture(item, null, index, true)
            TerminalHistorySource.DELIVERED ->
                if (JsonScalars.str(item, "type") == "reasoning") capture(item, bounds.at(index), index, true)
            TerminalHistorySource.ARRAY -> collectAt(item, at, index, true)
        }
    }

    private fun deliveredOrderConflicts(output: JsonArray): Boolean {
        if (!positionsProven) return true
        if (!state.emittedText) return false
        val terminalText = output.mapNotNull { it as? JsonObject }
            .filter { JsonScalars.str(it, "type") == NATIVE_MESSAGE_TYPE }
            .joinToString("", transform = ResponsesMessageText::text)
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
            NATIVE_MESSAGE_TYPE -> if (terminal) at?.text("assistant", ResponsesMessageText.text(item))
            NATIVE_FUNCTION_CALL_TYPE -> advanceTool(item, at)
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
                ReasoningItemOrder(round, outputIndex, terminal, id, terminalBatch = terminalBatch.takeIf { terminal }),
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
}

private enum class TerminalHistorySource { OWNED, DELIVERED, ARRAY }

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
            NATIVE_MESSAGE_TYPE, NATIVE_FUNCTION_CALL_TYPE -> IndexedValue(index, checkNotNull(item))
            else -> null
        }
    }
    private val first = context.firstOrNull()?.index
    private val last = context.lastOrNull()?.index
    private val callsObserved = context.filter { JsonScalars.str(it.value, "type") == NATIVE_FUNCTION_CALL_TYPE }
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
