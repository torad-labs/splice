// PORT-OF: ResponsesStreamTranslator.kt @ f875801 — invariants unchanged: the L3 terminal-precedence
// decision — the `TerminalStates(...).terminalPrecedence(...)` expression and the four
// `?:`-chained classifiers that feed it, kept together in ONE file, in ONE order, so a future L3
// landing has nowhere to accrete except here.
package splice.dialect.responses.stream

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome
import splice.core.util.ERR_SNIPPET
import splice.core.util.JsonScalars
import splice.dialect.responses.FailureReading
import splice.dialect.responses.ResponsesTurnState
import splice.dialect.responses.StreamTurnContext
import splice.dialect.responses.failure.ResponsesReplies
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.TerminalStates
import splice.upstream.retry.WatchdogFired
import splice.upstream.transport.TransportFaultLine
import java.io.IOException

private const val MS_PER_S = 1000L

// why: the vendor's text a failure event's evidence keeps for the journal, as the shared classifier capped it.
private const val FAILED_EVENT_MESSAGE_CHARS = 2000

internal class ResponsesTerminalDecision(
    private val ctx: StreamTurnContext,
    private val payload: ResponsesOutcomePayload,
    private val reading: FailureReading = FailureReading(),
) {

    // Ordering enforced by the shared spi.terminalPrecedence: a COMPLETED response wins over a
    // late watchdog fire (the watchdog polls the whole enclosing coroutine, including cleanup
    // after response.completed was already parsed — discarding that turn
    // would retry a successful compaction, the exact quota waste the watchdog exists to prevent).
    fun terminalOutcome(
        state: ResponsesTurnState,
        runawayGuard: String?,
        tear: IOException?,
    ): TurnOutcome = TerminalStates(
        // NF-06: a tripped runaway valve outranks everything — the buffers were truncated, so
        // neither a late terminal nor a provider error can describe this turn honestly. The valve is
        // splice's own guard on what the backend sent, a local verdict (G20).
        providerFailure = runawayGuard?.let {
            failed(FailureCause.Provider.ToolTear(runaway = true), it)
        } ?: state.toolArgsInvalid?.let {
            // CX-01: the backend sent a terminal, but a tool call's arguments are corrupt —
            // provider-reported (the backend produced the bytes) so it retries, never a clean
            // Success that dispatches garbage.
            failed(FailureCause.Provider.ToolTear(runaway = false), "upstream: $it in tool call; retry")
        } ?: state.upstreamFailure?.let { failedEvent(it, state) } ?: censored(state),
        finished = state.finalResponse != null,
        watchdogFired = ctx.signals.watchdogFired(),
    ).terminalPrecedence(
        onFinished = { payload.successOutcome(state) },
        onWatchdog = { watchdogOutcome(it, state) },
        onUnfinished = { noCompletionOutcome(state, tear) },
    )

    /**
     * A response.failed or error event the backend actually sent (G20 provenance), read once by the head's own reader
     * (failure-handling slice 3), which reads it as the same kind its HTTP twin is. Only a failure that is not
     * permanent carries the round's salvage, so a refusal, an overflow or a request the provider rejected never
     * re-POSTs the identical full context (V4-117, V4-81). An error no table names is not permanent
     * (OpenAiReplies): it carries its round too, code-less or not, and ResponsesReanchorPolicy sends it once more,
     * no more, as final-design.md section 8 plans for Unrecognized.
     */
    private fun failedEvent(event: JsonObject, state: ResponsesTurnState): TurnOutcome.Failure {
        val cause = read(ResponsesReplies.FAILED_EVENT, event)
        val partial = if (cause.permanent) null else payload.partialOrNull(state)
        val error = ResponsesReplies.error(event)
        // The journal keeps the event's own vocabulary: its code, else the event it came in, then the vendor's words.
        val code = error.code.ifEmpty { error.type }.ifEmpty { JsonScalars.strOrEmpty(event["type"]) }
            .ifEmpty { "upstream_failed" }
        val words = error.message.orEmpty().ifEmpty { "upstream reported failure" }
        return failed(cause, "upstream: ${"$code $words".take(FAILED_EVENT_MESSAGE_CHARS)}", partial)
    }

    /** The model's refusal, else the content filter. */
    private fun censored(state: ResponsesTurnState): TurnOutcome.Failure? =
        refusalFailure(state) ?: contentFilterFailure(state)

    // W4-A (L3): the backend REFUSED. OpenAI's refusal channel arrives with status `completed`, so
    // the incomplete-only gate below never sees it and the turn used to end as a clean Success
    // carrying zero text — or worse, as a normal turn once the pipeline promoted the streamed chain
    // of thought to the answer. The model's stated reason IS the verdict.
    // Deliberately carries NO `partial`, unlike every other failure here: a refusal is
    // deterministic, and ResponsesReanchorPolicy re-POSTs any API_ERROR that carries salvage
    // (RETRYABLE = {OVERLOADED, API_ERROR}), so a partial would buy an identical refusal at full
    // upstream cost. V4-81 and V4-122 item 11: the refusal the backend sent (G20) is a permanent
    // leaf. A permanent flag passed by hand was once LOST here while its comment survived, so the
    // pre-content rule relabelled the refusal overloaded_error and the client re-sent the identical
    // bytes up to 300 times for the same refusal. The leaf carries it now, so no site can drop it.
    // Failure-handling slice 3: the refusal part is read by the head's own reader, as the failure event is.
    private fun refusalFailure(state: ResponsesTurnState): TurnOutcome.Failure? =
        state.refusalBuf.toString().takeIf { it.isNotBlank() }?.let {
            failed(
                read(ResponsesReplies.REFUSAL_PART, buildJsonObject { put(ResponsesReplies.REFUSAL_PART, it) }),
                "upstream: model refused. $it",
            )
        }

    // response.incomplete with a non-max_output_tokens reason is a CENSORED turn — a clean
    // Success(incomplete=true) would let a blocked generation masquerade as complete (the same
    // L3 honesty invariant ChatStreamTranslator's contentFiltered branch closes). Carries NO
    // partial for the same reason as a refusal above: this terminal is deterministic, so handing
    // it to ResponsesReanchorPolicy would re-POST the full context for the identical verdict.
    // V4-81: the leaf is permanent for the same reason — the identical prompt is filtered
    // identically, so a retry buys the same censored turn at full price. Failure-handling slice 3: the head's own
    // reader reads the reason, and only content_filter is the filter; any other reason is a stop no table names.
    private fun contentFilterFailure(state: ResponsesTurnState): TurnOutcome.Failure? {
        if (!state.contentFiltered) return null
        val details = state.finalResponse?.get("incomplete_details") as? JsonObject
        val reason = JsonScalars.str(details, "reason").orEmpty()
        val cause = read(ResponsesReplies.INCOMPLETE_EVENT, buildJsonObject { put("reason", reason) })
        val why = if (cause is FailureCause.Provider.Refused) {
            "generation stopped by content filter"
        } else {
            "ended $reason"
        }
        return failed(cause, "upstream: $why")
    }

    /** A refusal part or an incomplete answer, read by the head's own reader as a failure event is. */
    private fun read(type: String, payload: JsonObject): FailureCause.Provider =
        reading.replies.event(FailedEvent(type, payload, reading.owner))

    // V4-242: a stream that tore says how, in the words of the error that tore it: the deepest cause,
    // which is the upstream's own close ("socket closed by the peer (status=1011, …) after …") when a
    // websocket peer ended it (TransportFaultLine.tear). A stream that simply stopped has nothing to add and reads as
    // it did.
    private fun noCompletionOutcome(state: ResponsesTurnState, tear: IOException?): TurnOutcome =
        if (ctx.signals.clientGone()) {
            TurnOutcome.ClientAbandoned()
        } else {
            val how = tear?.let(TransportFaultLine::tear)?.let { "truncated: ${it.take(ERR_SNIPPET)}" } ?: "truncated"
            val partial = payload.partialOrNull(state)
            val torn = FailureCause.Path.Torn(
                if (tear != null) Tear.SOCKET_CLOSED else Tear.NO_TERMINAL,
                toolOpen = partial?.toolTearOpen == true,
            )
            failed(torn, "splice: upstream stream ended without response.completed ($how); retry", partial)
        }

    // DR-7: an IDLE tear carries the round's salvage; a TOTAL-CAP tear does not, and the split is
    // the whole point. Idle is a STALL DETECTOR — the backend went quiet mid-part, the reasoning
    // already streamed to the client is real, and re-anchoring on it costs one POST and keeps the
    // turn. TotalCap is the whole-turn wall: handing it a partial would invite the continuation it
    // exists to forbid. Before this, BOTH built a Failure with no partial, so a stalled round threw
    // away text the client had already been shown and could not continue even in principle.
    private fun watchdogOutcome(fired: WatchdogFired, state: ResponsesTurnState): TurnOutcome {
        val why = when (fired) {
            // The tier that fired is named, not assumed: before any client frame the silence was
            // judged on the first-output cap (firstByteTimeoutMs), after it on streamIdle. An
            // operator reading "180s idle cap" on a 300s pre-output stall was reading a lie.
            is WatchdogFired.Idle -> if (fired.sawClientFrame) {
                "no completion within the ${fired.limitMs / MS_PER_S}s idle cap"
            } else {
                "no first output within the ${fired.limitMs / MS_PER_S}s first-output cap"
            }
            is WatchdogFired.TotalCap ->
                "no upstream progress for ${fired.idleMs / MS_PER_S}s"
        }
        val message = if (fired is WatchdogFired.TotalCap) {
            fired.retryMessage
        } else {
            "splice watchdog ended the round ($why); retry"
        }
        val partial = when (fired) {
            is WatchdogFired.Idle -> payload.partialOrNull(state)
            is WatchdogFired.TotalCap -> null
        }
        return failed(fired.cause, message, partial)
    }

    /** A failure of this round's stream. A watchdog fires DURING a stream too; whether a client frame actually
     *  went out is the emitter's to read when it answers, never guessed here. */
    private fun failed(
        cause: FailureCause,
        message: String,
        partial: TurnOutcome.PartialRound? = null,
    ): TurnOutcome.Failure = TurnOutcome.Failure(
        cause = cause,
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(message),
        partial = partial,
    )
}
