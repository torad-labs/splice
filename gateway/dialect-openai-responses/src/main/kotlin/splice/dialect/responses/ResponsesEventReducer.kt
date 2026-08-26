// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the dispatch tree and the
// terminal/failure event family — the ported shape of stream.mjs's runStreamTurn; the translator
// drives it and reads the accumulated state to render the terminal outcome.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier
import splice.spi.WireSink

private const val INCOMPLETE_REASON_MAX_TOKENS = "max_output_tokens"

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
            -> reasoningFold.onReasoningEvent(evt, sink)
            "response.output_text.delta" -> itemFold.onTextDelta(evt, sink)
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

    private fun onTerminal(evt: JsonObject) {
        val resp = (evt["response"] as? JsonObject) ?: evt
        state.finalResponse = resp
        if (JsonScalars.strOrEmpty(evt["type"]) == "response.incomplete" ||
            JsonScalars.strOrEmpty(resp["status"]) == "incomplete"
        ) {
            state.incomplete = true
            val reason = JsonScalars.str(resp["incomplete_details"] as? JsonObject, "reason").orEmpty()
            // max_output_tokens is the honest "ran out of room" stop; any other reason
            // (content_filter, etc.) is a censored generation, never a clean incomplete.
            if (reason.isNotEmpty() && reason != INCOMPLETE_REASON_MAX_TOKENS) state.contentFiltered = true
        }
        accumulateUsage(resp)
    }

    private fun onFailure(evt: JsonObject) {
        // v25 honesty: failure events were silently discarded and the turn finished as a
        // clean empty end-of-turn, corrupting the transcript. Capture; keep reading.
        val e = (evt["response"] as? JsonObject)?.get("error") as? JsonObject
            ?: evt["error"] as? JsonObject
            ?: evt
        val code = JsonScalars.strOrEmpty(e["code"])
            .ifEmpty { JsonScalars.strOrEmpty(e["type"]) }
            .ifEmpty { "upstream_failed" }
        val message = JsonScalars.strOrEmpty(e["message"]).ifEmpty { "ChatGPT backend reported failure" }
        state.upstreamFailure = UpstreamFailureClassifier.classify(FailureSource.SSE, "$code $message")
        // A response.failed payload can carry the round's usage — harvest it so the salvage
        // accounting is real (code-review 2026-07-24: the terminal-only harvest left
        // PartialRound.usage permanently zero).
        (evt["response"] as? JsonObject)?.let { accumulateUsage(it) }
    }

    /** Shared usage harvest for terminal AND failure payloads. Guarded >0 so a later, richer
     *  payload never zeroes an earlier one. */
    private fun accumulateUsage(resp: JsonObject) {
        val u = harvest.usageFrom(resp)
        if (u.inputTokens > 0) state.inputTokens = u.inputTokens
        if (u.outputTokens > 0) state.outputTokens = u.outputTokens
        if (u.cachedTokens > 0) state.cachedTokens = u.cachedTokens
        if (u.reasoningTokens > 0) state.reasoningTokens = u.reasoningTokens
    }
}
