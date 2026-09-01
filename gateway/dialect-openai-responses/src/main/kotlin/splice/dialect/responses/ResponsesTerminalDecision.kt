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
            // Parsed from a response.failed/error event the backend actually sent (G20 provenance).
            // Only an explicitly transient verdict carries re-anchor state: an unknown/policy error
            // must not re-POST the identical full context merely because API_ERROR is a wide bucket.
            TurnOutcome.Failure(
                it.type,
                "ChatGPT backend: ${it.message}",
                providerReported = true,
                partial = if (it.transient) payload.partialOrNull(state) else null,
            )
        } ?: refusalFailure(state) ?: contentFilterFailure(state),
        finished = state.finalResponse != null,
        watchdogFired = ctx.watchdogFired(),
    ).terminalPrecedence(
        onFinished = { payload.successOutcome(state) },
        onWatchdog = { watchdogOutcome(it, state) },
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
    // L3 honesty invariant ChatStreamTranslator's contentFiltered branch closes). Carries NO
    // partial for the same reason as a refusal above: this terminal is deterministic, so handing
    // it to ResponsesReanchorController would re-POST the full context for the identical verdict.
    private fun contentFilterFailure(state: ResponsesTurnState): TurnOutcome.Failure? =
        if (state.contentFiltered) {
            TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "ChatGPT backend: generation stopped by content filter",
                providerReported = true,
            )
        } else {
            null
        }

    private fun noCompletionOutcome(state: ResponsesTurnState): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned()
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "claudex: upstream stream ended without response.completed (truncated); retry",
                partial = payload.partialOrNull(state),
            )
        }

    // DR-7: an IDLE tear carries the round's salvage; a TOTAL-CAP tear does not, and the split is
    // the whole point. Idle is a STALL DETECTOR — the backend went quiet mid-part, the reasoning
    // already streamed to the client is real, and re-anchoring on it costs one POST and keeps the
    // turn. TotalCap is the whole-turn wall: handing it a partial would invite the continuation it
    // exists to forbid. Before this, BOTH built a Failure with no partial, so a stalled round threw
    // away text the client had already been shown and could not continue even in principle.
    private fun watchdogOutcome(fired: WatchdogFired, state: ResponsesTurnState): TurnOutcome {
        val why = when (fired) {
            is WatchdogFired.Idle ->
                "no completion within the ${ctx.streamIdleMsForMessage / MS_PER_S}s idle cap"
            is WatchdogFired.TotalCap ->
                "no completion within the ${ctx.upstreamTimeoutMsForMessage / MS_PER_S}s total cap"
        }
        return TurnOutcome.Failure(
            ErrorType.OVERLOADED,
            "claudex: upstream stream stalled ($why) — aborted; retry",
            partial = when (fired) {
                is WatchdogFired.Idle -> payload.partialOrNull(state)
                is WatchdogFired.TotalCap -> null
            },
        )
    }
}
