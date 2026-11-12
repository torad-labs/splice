package splice.dialect.responses.stream

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.reasoning.ResponsesReasoningFold
import splice.dialect.responses.reasoning.ResponsesReasoningReplay
import splice.upstream.sse.WireSink

/** Terminal harvest belongs before the final capacity decision and before pending presentation can flush. */
internal class ResponsesRoundCleanup(
    private val state: ResponsesTurnState,
    private val replay: ResponsesReasoningReplay,
    private val budget: ResponsesBufferBudget,
    private val reasoning: ResponsesReasoningFold,
) {
    suspend fun close(itemFold: ResponsesItemFold, sink: WireSink, terminalSeen: Boolean) =
        withContext(NonCancellable) {
            try {
                finishReasoning(sink)
                ResponsesTerminalBackfill().harvestFallback(state)
                replay.finish()
            } finally {
                // Also check a final frame or partial harvest that has no following event to run the PRE guard.
                budget.within(state, reasoning.pendingChars)
                reasoning.leadingText.closeSourceProgress(
                    itemFold,
                    terminalSeen,
                    sink,
                    capacityRejected = budget.failure != null,
                )
            }
        }

    /** Reconcile terminal-only summary tails before the harvest replaces the delivery buffer. */
    private suspend fun finishReasoning(sink: WireSink) {
        val output = state.finalResponse?.get("output") as? JsonArray ?: return
        output.forEachIndexed { index, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            val oi = ResponsesFrameParse().intOr(item["index"]) ?: index
            if (oi in reasoning.liveSummaryItems) reasoning.maybeEmitLateReasoning(item, oi, sink)
        }
    }
}
