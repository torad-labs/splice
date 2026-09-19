// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: the L3 verdict owner — six
// producers and one consumer (the ranked provider-failure `when`) in one file, strictly better
// co-location than sharing a file with tool-index synthesis and token accounting. Mirrors the
// passthrough dialect's PassthroughFailureRules idiom (commit 6868086).
package splice.dialect.chat

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.spi.ClassifiedFailure
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier

/** The chat dialect's honesty state machine: the flags that decide whether a turn's terminal is an
 *  honest Failure instead of a clean Success, and the finish_reason classification that feeds them.
 *  [toolCalls] is read through for CX-01's latch and written through for the `tool_calls`
 *  finish_reason, since [ChatToolCalls] owns the buffers those two facts are derived from. */
internal class ChatTerminalState(private val toolCalls: ChatToolCalls) {

    internal var finished = false
    internal var incomplete = false
    internal var contentFiltered = false
    internal var failure: ClassifiedFailure? = null

    // V4-167: whether the error event's verdict is one a re-send reproduces — see onError.
    private var failurePermanent = false

    // CX-08 (L3): OpenAI carries a model refusal in a DEDICATED `refusal` field on the streamed
    // delta and on the final message — never in `content`. Unread, the one text that explains the
    // turn was discarded: a refusal with no prose fell through to the pipeline's generic
    // "model returned no content (empty response) — retry" (locally synthesized, so the G20 health
    // split blamed splice for the backend's verdict, and the operator was told to retry a request
    // the backend will refuse identically), and a refusal that followed streamed reasoning ended as
    // a clean Success with the chain of thought promoted to the answer.
    internal val refusalBuf = StringBuilder()

    // WIRE-2/3/6: textBuf/thinkingBuf/toolIndexById exist for legitimate dedup/replay and must
    // stay unbounded on the normal path; this local safety valve (never provider-reported) only
    // trips far above any real response, cleanly failing the turn instead of a runaway upstream
    // growing them without limit.
    internal var runawayGuard: String? = null

    /** The upstream SSE error event, already pulled apart by [ChatEventRouter] (which owns the
     *  frame-shape knowledge). V4-164: classified by the SAME classifier every other path uses —
     *  this dialect was the one of three that kept only the text and called every in-band error
     *  UPSTREAM_REPORTED, so an overflow reached Claude Code as a retried api_error instead of the
     *  "prompt is too long" it compacts on, and a local runtime's KV-pool failure as bare text. */
    internal fun onError(message: String, kind: String, status: Int?) {
        val classified = UpstreamFailureClassifier.classify(FailureSource.SSE, message, status, kind)
        failure = classified
        // V4-167: a verdict the vendor TYPED or gave a status is carried as the classifier scored it,
        // as ResponsesTerminalDecision has since V4-81, so a deterministic error is not advertised as
        // retryable. An event with neither keeps the wire V4-164 promised it: retryable, as before.
        failurePermanent = !classified.transient && (status != null || kind.isNotBlank())
    }

    internal fun onFinish(reason: String) {
        finished = true
        when (reason) {
            "tool_calls" -> toolCalls.hasToolUse = true
            // OpenAI standard is "length"; several OpenAI-compat vendors also emit "max_tokens".
            "length", "max_tokens" -> incomplete = true
            "content_filter" -> contentFiltered = true
            else -> Unit // stop / others -> end_turn
        }
    }

    /** The ranked provider-failure verdict, or null when the turn has none. */
    internal fun providerFailure(): TurnOutcome.Failure? {
        val runaway = runawayGuard
        val reported = failure
        return when {
            runaway != null -> TurnOutcome.Failure(
                runaway,
                providerReported = false,
                cause = FailureCause.TOOL_TEAR,
                phase = FailurePhase.MID_OUTPUT,
            )
            // CX-01: a terminated turn whose tool arguments are corrupt must not reach the client
            // as a Success — provider-reported (the backend produced the bytes), so it retries.
            toolCalls.toolArgsInvalid != null -> TurnOutcome.Failure(
                "chat backend: ${toolCalls.toolArgsInvalid} in tool call — retry",
                providerReported = true,
                cause = FailureCause.TOOL_TEAR,
                phase = FailurePhase.MID_OUTPUT,
            )
            reported != null ->
                TurnOutcome.Failure(
                    "chat backend: ${reported.message}",
                    providerReported = true,
                    // V4-164: the classifier's cause, carried through untouched — the passthrough
                    // dialect's rule (PassthroughTerminalState: "an earlier draft hard-coded
                    // UPSTREAM_REPORTED here and threw both verdicts away"). An event with no
                    // recognisable shape still lands on UPSTREAM_REPORTED, from the classifier's
                    // own floor, so an unknown in-band error keeps exactly the wire it had.
                    cause = reported.cause,
                    permanent = failurePermanent,
                    phase = FailurePhase.MID_OUTPUT,
                )
            // CX-08: the backend populated `refusal` — a censored generation whose STATED REASON is
            // the only honest verdict available. Ranked above the content_filter branch below
            // because it carries the model's own words instead of a generic phrase; it outranks
            // `finished` for the same reason content_filter does (the same frame sets both).
            // isNotBlank, not isNotEmpty: a buffer of only whitespace fragments must still read as
            // "no refusal" now that fragments are accepted verbatim (round-2 review).
            refusalBuf.isNotBlank() -> TurnOutcome.Failure(
                "chat backend: model refused — $refusalBuf",
                providerReported = true, // the `refusal` the backend sent, not a local verdict (G20)
                // V4-81, the responses dialect's sibling (found by sweeping rather than by being
                // told): a refusal reproduces exactly, so advertising it as transient buys the same
                // refusal again — see PreContentWireType. The two dialects answer the same question
                // the same way or the behaviour depends on which head the operator happens to run.
                permanent = true,
                cause = FailureCause.MODEL_REFUSED,
                phase = FailurePhase.TERMINAL,
            )
            // finish_reason=content_filter is a CENSORED turn — a clean end_turn would let a
            // blocked generation masquerade as complete (honesty invariant); it outranks
            // `finished` (the same frame sets both). Retry an api_error honestly.
            contentFiltered -> TurnOutcome.Failure(
                "chat backend: generation stopped by content filter",
                providerReported = true, // finish_reason the backend sent, not a local verdict (G20)
                permanent = true, // V4-81: the identical prompt is filtered identically.
                cause = FailureCause.CONTENT_FILTERED,
                phase = FailurePhase.TERMINAL,
            )
            else -> null
        }
    }
}
