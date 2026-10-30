// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the dispatch tree and the
// terminal/failure event family — the ported shape of stream.mjs's runStreamTurn; the translator
// drives it and reads the accumulated state to render the terminal outcome.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.reasoning.ResponsesReasoningFold
import splice.upstream.sse.WireSink

private const val INCOMPLETE_REASON_MAX_TOKENS = "max_output_tokens"
private const val KEY_RESPONSE = "response"
private const val KEY_ERROR = "error"
private const val REASONING_ITEM = "reasoning"

/**
 * Folds the upstream SSE event stream into per-turn buffers/flags via its collaborating folds. One
 * private handler per upstream event family.
 */
internal class ResponsesEventReducer(
    private val state: ResponsesTurnState,
    private val itemFold: ResponsesItemFold,
    private val reasoningFold: ResponsesReasoningFold,
) {

    private val harvest = ResponsesHarvest()

    suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (JsonScalars.strOrEmpty(evt["type"])) {
            "response.completed", "response.done", "response.incomplete" -> onTerminal(evt)
            "response.failed", "response.error", "error" -> onFailure(evt)
            else -> onStreamEvent(evt, sink)
        }
    }

    private suspend fun onStreamEvent(evt: JsonObject, sink: WireSink) {
        when (JsonScalars.strOrEmpty(evt["type"])) {
            "response.output_item.added" -> itemFold.onItemAdded(evt, sink)
            "response.output_item.done" -> itemFold.onItemDone(evt, sink)
            // The reasoning family dispatches inside the fold (one arm here keeps this method
            // under the complexity ceiling). Includes reasoning_summary_text.done — the
            // sequential_cutoff render surface (codex parity 2026-08-26).
            "response.reasoning_summary_part.added",
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            "response.reasoning_summary_text.done",
            "response.reasoning_summary_part.done",
            -> reasoningFold.onReasoningEvent(evt, sink)
            "response.custom_tool_call_input.delta" -> itemFold.execProgress.delta(evt, sink)
            "response.output_text.delta", "response.output_text.done" -> onTextEvent(evt, sink)
            "response.function_call_arguments.delta", "response.function_call_arguments.done" ->
                itemFold.onArgs(evt, sink)
            // W4-A (L3): the streamed refusal carriers. Both used to fall into the `else` below
            // and the turn finished clean with no text at all. `.done` is not an edge case — the
            // documented order is refusal.delta xN -> refusal.done -> content_part.done, and
            // ResponseRefusalDoneEvent carries the COMPLETE refusal string, so a backend that
            // finalizes without streaming deltas is covered only here.
            "response.refusal.delta", "response.refusal.done" -> state.addRefusal(evt)
            // *_text.done / *_part.done fire PER PART — closing here was the v24 truncation bug
            else -> Unit
        }
    }

    private suspend fun onTextEvent(evt: JsonObject, sink: WireSink) {
        if (JsonScalars.str(evt, "type") == "response.output_text.done") {
            itemFold.onTextDone(evt, sink)
        } else {
            itemFold.onTextDelta(evt, sink)
        }
    }

    private fun onTerminal(evt: JsonObject) {
        val resp = (evt[KEY_RESPONSE] as? JsonObject) ?: evt
        state.finalResponse = resp
        if (JsonScalars.strOrEmpty(evt["type"]) == "response.incomplete" ||
            JsonScalars.strOrEmpty(resp["status"]) == "incomplete"
        ) {
            state.incomplete = true
            val reason = JsonScalars.str(resp["incomplete_details"] as? JsonObject, "reason").orEmpty()
            state.ending = "${word(JsonScalars.str(evt["type"]))} reason=${word(reason)}"
            // max_output_tokens is the honest "ran out of room" stop; any other reason
            // (content_filter, etc.) is a censored generation, never a clean incomplete.
            if (reason.isNotEmpty() && reason != INCOMPLETE_REASON_MAX_TOKENS) state.contentFiltered = true
        } else if (endedInReasoning(resp)) {
            state.messageClosed = true
            state.endedInReasoning = true
        }
        accumulateUsage(resp)
    }

    /** A completed response whose whole output is reasoning is the model ending its answer with nothing to say, as
     *  codex-rs reads it: no tool call, so no follow-up, and the turn ends with no message. Claude Code's
     *  prompt-suggestion request asks for exactly that silence, and Muse answers it this way (50 empty_model turns on
     *  Oct 12, each re-sent by splice and then by the client). An empty output, or an item type this dialect never
     *  renders, stays the empty-turn error. */
    private fun endedInReasoning(resp: JsonObject): Boolean {
        if (JsonScalars.strOrEmpty(resp["status"]) != "completed") return false
        val output = (resp["output"] as? JsonArray)?.map { (it as? JsonObject)?.get("type") }.orEmpty()
        // Retained stream history is not a completed message or proof of a finished silent answer.
        return output.isNotEmpty() && output.all { JsonScalars.strOrEmpty(it) == REASONING_ITEM }
    }

    private fun onFailure(evt: JsonObject) {
        // v25 honesty: failure events were silently discarded and the turn finished as a
        // clean empty end-of-turn, corrupting the transcript. Capture; keep reading. The event is kept as the backend
        // sent it, and the head's own reader reads it once at the terminal (failure-handling slice 3), where the shared
        // classifier read a flattened "code message" string here until then.
        state.upstreamFailure = evt
        val e = (evt[KEY_RESPONSE] as? JsonObject)?.get(KEY_ERROR) as? JsonObject
            ?: evt[KEY_ERROR] as? JsonObject
            ?: evt
        // On the FLAT error event the payload IS the event (e === evt), so `type` is the SSE event discriminator
        // ("error"/"response.failed"), never the vendor's error type, and the ending line does not name it as one.
        val typeField = JsonScalars.strOrEmpty(e["type"])
        state.ending = "${word(JsonScalars.str(evt["type"]))} status=${word(JsonScalars.str(evt["status"]))} " +
            "code=${word(JsonScalars.str(e["code"]))} type=${word(if (e === evt) null else typeField)}"
        // A response.failed payload can carry the round's usage — harvest it so the salvage
        // accounting is real (code-review 2026-07-24: the terminal-only harvest left
        // PartialRound.usage permanently zero).
        (evt[KEY_RESPONSE] as? JsonObject)?.let { accumulateUsage(it) }
    }

    /** An upstream protocol word for the ending line: "-" when absent, "?" when it is anything but a short code, so
     *  no message text can ride a field the backend filled. */
    private fun word(value: String?): String = when {
        value.isNullOrEmpty() -> "-"
        protocolWord.matches(value) -> value
        else -> "?"
    }

    private val protocolWord = Regex("[A-Za-z0-9_.:-]{1,64}")

    /** Shared usage harvest for terminal AND failure payloads. Guarded >0 so a later, richer
     *  payload never zeroes an earlier one. */
    private fun accumulateUsage(resp: JsonObject) {
        val u = harvest.usageFrom(resp)
        state.reportedUsage += u.reported
        if (u.inputTokens > 0) state.inputTokens = u.inputTokens
        if (u.outputTokens > 0) state.outputTokens = u.outputTokens
        if (u.cachedTokens > 0) state.cachedTokens = u.cachedTokens
        if (u.reasoningTokens > 0) state.reasoningTokens = u.reasoningTokens
    }
}
