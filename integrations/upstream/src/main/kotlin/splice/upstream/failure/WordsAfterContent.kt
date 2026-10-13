// NEW: splice-lead's ruling from splice-turns' law audit (Oct 12): a refusal or a content filter that
// arrives after content has streamed ends as words appended to the answer, on every dialect.
package splice.upstream.failure

import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome

/**
 * Once the client has been shown content, Claude Code never retries the turn, and an error frame
 * there reads only as a lost connection. A refusal or a content filter at that point is a verdict
 * the person should read. So it is marked deterministic, which the pipeline renders as words after
 * the answer, the ending codex code-mode and a stall after content already use. Before any content
 * the failure is left as it was, an error the client shows.
 */
public object WordsAfterContent {
    private val VERDICTS = setOf(FailureCause.MODEL_REFUSED, FailureCause.CONTENT_FILTERED)

    /** [failure] as it should end, given whether the client has already been shown content. */
    public fun of(failure: TurnOutcome.Failure?, contentShown: Boolean): TurnOutcome.Failure? =
        failure?.takeIf { contentShown && it.cause in VERDICTS }
            ?.let { it.copy(traits = it.traits.copy(deterministic = true)) }
            ?: failure
}
