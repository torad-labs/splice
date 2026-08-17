// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the L3 terminal-precedence
// decision — the `TerminalStates(...).terminalPrecedence(...)` expression and the four
// `?:`-chained classifiers that feed it, kept together in ONE file, in ONE order, so a future L3
// landing has nowhere to accrete except here.
package splice.dialect.responses

import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.TerminalStates
import splice.spi.WatchdogFired

private const val MS_PER_S = 1000L

internal class ResponsesTerminalDecision(
    private val ctx: StreamTurnContext,
    private val payload: ResponsesOutcomePayload,
) {

    // Ordering enforced by the shared spi.terminalPrecedence: a COMPLETED response wins over a
    // late watchdog fire (the watchdog polls the whole enclosing coroutine, which stays suspended
    // on the socket-EOF read AFTER response.completed was already parsed — discarding that turn
    // would retry a successful compaction, the exact quota waste the watchdog exists to prevent).
    fun terminalOutcome(state: ResponsesTurnState, runawayGuard: String?): TurnOutcome = TerminalStates(
        // NF-06: a tripped runaway valve outranks everything — the buffers were truncated, so
        // neither a late terminal nor a provider error can describe this turn honestly.
        providerFailure = runawayGuard?.let {
            TurnOutcome.Failure(ErrorType.API_ERROR, it, providerReported = false)
        } ?: state.toolArgsInvalid?.let {
            // CX-01: the backend sent a terminal, but a tool call's arguments are corrupt —
            // provider-reported (the backend produced the bytes) so it retries, never a clean
            // Success that dispatches garbage.
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: $it in tool call — retry",
                providerReported = true,
            )
        } ?: state.upstreamFailure?.let {
            // parsed from a response.failed/error event the backend actually sent (G20 provenance)
            TurnOutcome.Failure(
                it.type,
                "ChatGPT backend: ${it.message}",
                providerReported = true,
                partial = payload.partialOrNull(state),
            )
        } ?: refusalFailure(state) ?: contentFilterFailure(state),
        finished = state.finalResponse != null,
        watchdogFired = ctx.watchdogFired(),
    ).terminalPrecedence(
        onFinished = { payload.successOutcome(state) },
        onWatchdog = ::watchdogOutcome,
        onUnfinished = { noCompletionOutcome(state) },
    )

    // W4-A (L3): the backend REFUSED. OpenAI's refusal channel arrives with status `completed`, so
    // the incomplete-only gate below never sees it and the turn used to end as a clean Success
    // carrying zero text — or worse, as a normal turn once the pipeline promoted the streamed chain
    // of thought to the answer. The model's stated reason IS the verdict.
    // Deliberately carries NO `partial`, unlike every other failure here: a refusal is
    // deterministic, and ResponsesReanchorController re-POSTs any API_ERROR that carries salvage
    // (RETRYABLE = {OVERLOADED, API_ERROR}), so a partial would buy an identical refusal at full
    // upstream cost.
    private fun refusalFailure(state: ResponsesTurnState): TurnOutcome.Failure? =
        state.refusalBuf.toString().takeIf { it.isNotBlank() }?.let {
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: model refused — $it",
                providerReported = true, // the `refusal` the backend sent, not a local verdict (G20)
            )
        }

    // response.incomplete with a non-max_output_tokens reason is a CENSORED turn — a clean
    // Success(incomplete=true) would let a blocked generation masquerade as complete (the same
    // L3 honesty invariant ChatStreamTranslator's contentFiltered branch closes).
    private fun contentFilterFailure(state: ResponsesTurnState): TurnOutcome.Failure? =
        if (state.contentFiltered) {
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: generation stopped by content filter",
                providerReported = true,
                partial = payload.partialOrNull(state),
            )
        } else {
            null
        }

    private fun noCompletionOutcome(state: ResponsesTurnState): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "claudex: upstream stream ended without response.completed (truncated); retry",
                partial = payload.partialOrNull(state),
            )
        }

    private fun watchdogOutcome(fired: WatchdogFired): TurnOutcome {
        val why = when (fired) {
            is WatchdogFired.Idle ->
                "no completion within the ${ctx.streamIdleMsForMessage / MS_PER_S}s idle cap"
            is WatchdogFired.TotalCap ->
                "no completion within the ${ctx.upstreamTimeoutMsForMessage / MS_PER_S}s total cap"
        }
        return TurnOutcome.Failure(
            ErrorType.OVERLOADED,
            "claudex: upstream stream stalled ($why) — aborted; retry",
        )
    }
}
