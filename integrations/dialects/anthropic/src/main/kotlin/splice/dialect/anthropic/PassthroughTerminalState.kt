// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the L3 verdict owner.
// Every producer of the ranked provider-failure expression lives here WITH the ranking that reads
// it — extracting one producer on its own creates a window where a field is set and never read.
// The two verdict-message rules keep riding PassthroughFailureRules (commit 6868086's idiom), so
// the `else ->` remainder is still handed to stopReasonFailure at the same call site.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Lift
import splice.core.failure.LoginOwner
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.RefusedBy
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.upstream.failure.UpstreamFailureClassifier

private const val CONTEXT_EXCEEDED_MESSAGE =
    "generation stopped: the model context window was exceeded (stop_reason=model_context_window_exceeded)"

/** The passthrough dialect's honesty state machine: the flags and latches that decide whether a
 *  turn's terminal is an honest Failure instead of a clean Success, plus the stop_reason
 *  classification that feeds them. [blocks] is written through for the `tool_use` stop_reason,
 *  since [PassthroughBlockRegistry] owns the tool-use fact the outcome is derived from. */
internal class PassthroughTerminalState(
    private val quirks: PassthroughQuirks,
    private val blocks: PassthroughBlockRegistry,
) {

    internal var incomplete = false
    internal var finished = false
    private var failureCause: FailureCause.Provider? = null
    private var failureMessage: String = ""

    // NF-06: latched when BufferCapacity trips; never provider-reported (the verdict is local).
    private var runawayGuard: String? = null

    // The two verdict-message rules ride a collaborator rather than this class: they read only
    // their arguments — no state, no wire access — so they are a rule table, not turn state.
    private val failureRules = PassthroughFailureRules()

    /** NF-06: the runaway valve tripped in driveTurn (which owns the BufferCapacity call site, one
     *  cap for three dialects). The latch is a producer of the ranked verdict, so it lives here. */
    internal fun latchRunawayGuard() {
        runawayGuard = failureRules.runawayGuardMessage(quirks.providerTag)
    }

    internal fun onStopReason(reason: String, details: JsonObject?) {
        when (reason) {
            "tool_use" -> blocks.hasToolUse = true
            "max_tokens" -> incomplete = true
            // CX-07 (L3): every OTHER value used to land in a bare `else -> Unit` with end_turn
            // semantics, so a generation the BACKEND refused, paused or hard-truncated reached the
            // client as a clean, complete Success. The three non-clean Anthropic terminals now latch
            // the provider slot; end_turn / stop_sequence / an absent stop_reason / an unrecognized
            // vendor value keep end_turn semantics (see [PassthroughFailureRules.stopReasonFailure]).
            // First latch wins so a later genuine `error` event can never be overwritten by a
            // trailing message_delta.
            else -> failureRules.stopReasonFailure(reason, details)?.let { (cause, why) ->
                if (failureCause == null) {
                    failureCause = cause
                    failureMessage = why
                }
            }
        }
    }

    /** The upstream SSE error event, already pulled apart by [PassthroughEventRouter] (which owns
     *  the frame-shape knowledge) — same classification, same order, receiver became argument. */
    internal fun onError(type: String, message: String) {
        // V4-117: the verdict is a CAUSE now. Each arm names the condition the vendor's own error
        // type describes, and ClientWire derives the wire type from it — exactly the ErrorType this
        // table used to name by hand (overloaded → OVERLOADED, rate_limit → RATE_LIMIT, authentication →
        // AUTHENTICATION, invalid_request → INVALID_REQUEST, and an unrecognised type → API_ERROR).
        failureCause = when (type) {
            "overloaded_error" -> FailureCause.Provider.Overloaded(Lift.Unstated, loading = false)
            "rate_limit_error" -> FailureCause.Provider.Limited(Lift.Unstated, window = null)
            "authentication_error" -> FailureCause.Provider.AuthRejected(LoginOwner.SPLICE)
            "invalid_request_error" -> FailureCause.Provider.Rejected(staleReasoning = false)
            else -> FailureCause.Provider.Unrecognized(clientError = false)
        }
        failureMessage = message.ifEmpty { "error" }
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
            // a non-clean stop_reason (CX-07, see [PassthroughFailureRules.stopReasonFailure]) —
            // provider-reported (G20)
            TurnOutcome.Failure(
                // V4-117: the CAUSE the two producers above latched (onError's vendor type, or
                // stopReasonFailure's stop_reason verdict), carried through untouched. An earlier
                // draft hard-coded UPSTREAM_REPORTED here and threw both verdicts away, which seven
                // tests caught: every SSE error arrived as API_ERROR regardless of what the vendor
                // had called it.
                cause = it,
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence("${quirks.providerTag}: $failureMessage"),
            )
        }
}

/**
 * The translator's two verdict-message rules, on their own type: both read only their arguments —
 * no state, no wire access.
 */
private class PassthroughFailureRules {

    /** NF-06 runaway-upstream guard message; the cap lives in spi.BufferCapacity (one source,
     *  three dialects). */
    fun runawayGuardMessage(providerTag: String): String =
        "$providerTag: response exceeded max buffered size; aborting"

    /**
     * CX-07 (L3): the Anthropic `stop_reason` values that are NOT a clean completion, mapped to the
     * honest failure each one is. The protocol ships SEVEN stop reasons; four are clean — `end_turn`,
     * `stop_sequence`, `tool_use`, `max_tokens` — and these three are not:
     *   · `refusal` — the model refused to generate; a censored turn, same class as Chat's content_filter;
     *   · `pause_turn` — the backend paused a long-running turn; retryable, not a finished answer;
     *   · `model_context_window_exceeded` — a HARD truncation distinct from `max_tokens`, so it must not
     *     fold into `incomplete` (that would report the ordinary "ran out of room" stop for a turn the
     *     backend could not run at all).
     * Returns null for a clean value, for an absent stop_reason (`""`), and for an unrecognized one.
     *
     * WHY THE REMAINDER STAYS OPEN-SAFE instead of failing everything outside the clean four (decision
     * 2026-08-09; the open-remainder alternative was considered and rejected): this dialect serves
     * exactly one backend, Kimi's `/coding` surface, and OpenAI-compatible vendors demonstrably invent
     * enumeration values — `ChatTerminalState.onFinish` already carries a `max_tokens` arm for
     * vendors that ignore the spec's `length`. An open remainder would turn one off-spec value on a
     * WORKING turn into a failed turn, and a false Failure on working traffic is worse than the silent
     * success it replaces. The cost is that stop_reason #8 arrives invisible; making unknown
     * discriminator values COUNTED needs a per-turn telemetry channel the dialects do not have (see the
     * ledger note on W4-A) and is proposed as its own item rather than smuggled in here.
     */
    // V4-117: a CAUSE per arm, not a type. ClientWire derives the wire types from the cause (refusal →
    // INVALID_REQUEST since b21329586, pause_turn → OVERLOADED, context-exceeded → INVALID_REQUEST), and the
    // overflow arm keeps the classifier's own words for an overflow.
    fun stopReasonFailure(reason: String, details: JsonObject?): Pair<FailureCause.Provider, String>? = when (reason) {
        "refusal" -> FailureCause.Provider.Refused(RefusedBy.MODEL) to refusalMessage(details)
        "pause_turn" -> FailureCause.Provider.Overloaded(Lift.Unstated, loading = false) to
            "backend paused the turn (stop_reason=pause_turn); retry"
        "model_context_window_exceeded" ->
            FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = true) to
                UpstreamFailureClassifier.overflowFailure(CONTEXT_EXCEEDED_MESSAGE).message
        else -> null
    }

    private fun refusalMessage(details: JsonObject?): String = buildString {
        append("generation was blocked by the upstream safety classifier (stop_reason=refusal).")
        val category = JsonScalars.strIfString(details?.get("category")).takeIf { it.isNotBlank() }
        val explanation = JsonScalars.strIfString(details?.get("explanation")).takeIf { it.isNotBlank() }
        category?.let { append(" Refusal category: $it.") }
        explanation?.let { append(" $it") }
        if (category == null && explanation == null) {
            append(" The upstream did not provide a refusal category or explanation.")
        }
        append(" Review the request before retrying.")
    }
}
