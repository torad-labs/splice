// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the L3 verdict owner.
// Every producer of the ranked provider-failure expression lives here WITH the ranking that reads
// it — extracting one producer on its own creates a window where a field is set and never read.
// Failure-handling slice 3: an error event and a stop reason that is no clean end are read once, by the head's own
// reader (AnthropicReplies with the head's deviation), so the type and stop-reason tables this file kept are gone.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.anthropic.failure.AnthropicReplies
import splice.upstream.failure.ERROR_EVENT
import splice.upstream.failure.FailedEvent

/** The log's words for an answer that outgrew the window, as the classifier wrote them before slice 3. */
private const val CONTEXT_EXCEEDED_MESSAGE =
    "prompt is too long: generation stopped: the model context window was exceeded " +
        "(stop_reason=model_context_window_exceeded)"

/** The passthrough dialect's honesty state machine: the flags and latches that decide whether a
 *  turn's terminal is an honest Failure instead of a clean Success. [blocks] is written through for the `tool_use`
 *  stop_reason, since [PassthroughBlockRegistry] owns the tool-use fact the outcome is derived from, and [ctx] carries
 *  the head's reader, which reads an error event and a stop reason that is no clean end. */
internal class PassthroughTerminalState(
    private val quirks: PassthroughQuirks,
    private val blocks: PassthroughBlockRegistry,
    private val ctx: PassthroughTurnContext,
) {

    internal var incomplete = false
    internal var finished = false
    private var failureCause: FailureCause.Provider? = null
    private var failureMessage: String = ""

    // NF-06: latched when BufferCapacity trips; never provider-reported (the verdict is local).
    private var runawayGuard: String? = null

    // The two log-message rules ride a collaborator rather than this class: they read only
    // their arguments — no state, no wire access — so they are a rule table, not turn state.
    private val failureRules = PassthroughFailureRules()

    /** NF-06: the runaway valve tripped in driveTurn (which owns the BufferCapacity call site, one
     *  cap for three dialects). The latch is a producer of the ranked verdict, so it lives here. */
    internal fun latchRunawayGuard() {
        runawayGuard = failureRules.runawayGuardMessage(quirks.providerTag)
    }

    /** [delta] is the message_delta's own delta object, which carries [reason] and the stop_details beside it. */
    internal fun onStopReason(reason: String, delta: JsonObject?) {
        when (reason) {
            "tool_use" -> blocks.hasToolUse = true
            "max_tokens" -> incomplete = true
            // CX-07 (L3): a generation the backend refused, paused or hard-truncated is no clean, complete
            // Success, and the head's reader says what it is. end_turn / stop_sequence / an absent stop_reason / an
            // unrecognized vendor value keep end_turn semantics (AnthropicReplies.FAILED_STOPS). First latch wins
            // so a later genuine `error` event can never be overwritten by a trailing message_delta.
            in AnthropicReplies.FAILED_STOPS -> if (failureCause == null) {
                val stop = delta ?: JsonObject(emptyMap())
                failureCause = ctx.replies.event(FailedEvent(AnthropicReplies.STOP_EVENT, stop, ctx.owner))
                failureMessage = failureRules.stopMessage(reason, stop["stop_details"] as? JsonObject)
            }
            else -> Unit
        }
    }

    /** The upstream SSE error event as sent, read once by the head's reader. */
    internal fun onError(event: JsonObject) {
        // Each error type the vendor sends names its condition, and the reader turns it into the cause ClientWire
        // derives the wire type from; the vendor's message rides in the log beside it.
        failureCause = ctx.replies.event(FailedEvent(ERROR_EVENT, event, ctx.owner))
        failureMessage = JsonScalars.strOrEmpty((event["error"] as? JsonObject)?.get("message")).ifEmpty { "error" }
    }

    /** The ranked provider-failure verdict, or null when the turn has none. */
    internal fun providerFailure(): TurnOutcome.Failure? =
        // NF-06: a tripped runaway valve outranks the provider slot — the buffers were truncated.
        runawayGuard?.let {
            TurnOutcome.Failure(
                // splice's own guard, never provider-reported. V4-81: permanent, and it is the same argument the
                // responses dialect's runaway arm makes by construction — the valve trips on the GENERATION ITSELF
                // hitting its truncation bound, so re-sending the identical request reproduces the identical
                // overrun. Marking it permanent is what stops the pre-content rule advertising a condition a retry
                // cannot change as transient.
                cause = FailureCause.Provider.ToolTear(runaway = true, permanent = true),
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence(it),
            )
        } ?: failureCause?.let {
            // a signal the BACKEND sent — an upstream SSE error event (e.g. overloaded_error) or
            // a non-clean stop_reason (CX-07) — provider-reported (G20)
            TurnOutcome.Failure(
                // V4-117: the CAUSE the two producers above latched (the reader's reading of the error event or of
                // the stop reason), carried through untouched. An earlier draft hard-coded UPSTREAM_REPORTED here
                // and threw both verdicts away, which seven tests caught: every SSE error arrived as API_ERROR
                // regardless of what the vendor had called it.
                cause = it,
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence("${quirks.providerTag}: $failureMessage"),
            )
        }
}

/**
 * The translator's two log-message rules, on their own type: both read only their arguments —
 * no state, no wire access.
 */
private class PassthroughFailureRules {

    /** NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source,
     *  three dialects). */
    fun runawayGuardMessage(providerTag: String): String =
        "$providerTag: response exceeded max buffered size; aborting"

    /** The log's account of a stop reason that is no clean end, naming what the backend sent and advising nothing:
     *  a refusal's category and explanation whole, as failure-handling slice 2 wrote them. */
    fun stopMessage(reason: String, details: JsonObject?): String = when (reason) {
        "model_context_window_exceeded" -> CONTEXT_EXCEEDED_MESSAGE
        "refusal" -> {
            val category = detail(details, "category")?.let { " category=$it" }.orEmpty()
            val explanation = detail(details, "explanation")?.let { ": $it" }.orEmpty()
            "stop_reason=refusal$category$explanation"
        }
        else -> "stop_reason=$reason"
    }

    private fun detail(details: JsonObject?, key: String): String? =
        JsonScalars.strIfString(details?.get(key)).takeIf { it.isNotBlank() }
}
