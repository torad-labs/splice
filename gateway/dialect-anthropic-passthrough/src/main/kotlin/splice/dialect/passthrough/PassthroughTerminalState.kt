// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: the L3 verdict owner.
// Every producer of the ranked provider-failure expression lives here WITH the ranking that reads
// it — extracting one producer on its own creates a window where a field is set and never read.
// The two verdict-message rules keep riding PassthroughFailureRules (commit 6868086's idiom), so
// the `else ->` remainder is still handed to stopReasonFailure at the same call site.
package splice.dialect.passthrough

import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome

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
    private var failureType: ErrorType? = null
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

    internal fun onStopReason(reason: String) {
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
            else -> failureRules.stopReasonFailure(reason)?.let { (type, why) ->
                if (failureType == null) {
                    failureType = type
                    failureMessage = why
                }
            }
        }
    }

    /** The upstream SSE error event, already pulled apart by [PassthroughEventRouter] (which owns
     *  the frame-shape knowledge) — same classification, same order, receiver became argument. */
    internal fun onError(type: String, message: String) {
        failureType = when (type) {
            "overloaded_error" -> ErrorType.OVERLOADED
            "rate_limit_error" -> ErrorType.RATE_LIMIT
            "authentication_error" -> ErrorType.AUTHENTICATION
            "invalid_request_error" -> ErrorType.INVALID_REQUEST
            else -> ErrorType.API_ERROR
        }
        failureMessage = message.ifEmpty { "error" }
    }

    /** The ranked provider-failure verdict, or null when the turn has none. */
    internal fun providerFailure(): TurnOutcome.Failure? =
        // NF-06: a tripped runaway valve outranks the provider slot — the buffers were truncated.
        runawayGuard?.let {
            TurnOutcome.Failure(ErrorType.API_ERROR, it, providerReported = false)
        } ?: failureType?.let {
            // a signal the BACKEND sent — an upstream SSE error event (e.g. overloaded_error) or
            // a non-clean stop_reason (CX-07, see [PassthroughFailureRules.stopReasonFailure]) —
            // provider-reported (G20)
            TurnOutcome.Failure(it, "${quirks.providerTag}: $failureMessage", providerReported = true)
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
        "$providerTag: response exceeded max buffered size — aborting"

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
    fun stopReasonFailure(reason: String): Pair<ErrorType, String>? = when (reason) {
        "refusal" -> ErrorType.API_ERROR to "generation refused by the model (stop_reason=refusal)"
        "pause_turn" -> ErrorType.OVERLOADED to "backend paused the turn (stop_reason=pause_turn) — retry"
        "model_context_window_exceeded" -> ErrorType.API_ERROR to CONTEXT_EXCEEDED_MESSAGE
        else -> null
    }
}
