// Provider refusal and content-filter verdicts remain terminal after partial output.
package splice.upstream.failure

import splice.core.turn.FailureCause
import splice.core.turn.TurnOutcome

/**
 * A refusal or content filter after partial output is a final verdict. Preserve that property in
 * the failure traits; the pipeline delivers it through the error channel without appending prose.
 */
public object WordsAfterContent {
    private val VERDICTS = setOf(FailureCause.MODEL_REFUSED, FailureCause.CONTENT_FILTERED)

    /** [failure] as it should end, given whether the client has already been shown content. */
    public fun of(failure: TurnOutcome.Failure?, contentShown: Boolean): TurnOutcome.Failure? =
        failure?.takeIf { contentShown && it.cause in VERDICTS }
            ?.let { it.copy(traits = it.traits.copy(deterministic = true)) }
            ?: failure
}
