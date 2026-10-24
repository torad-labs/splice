package splice.dialect.responses.stream

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
}
