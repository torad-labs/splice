// NEW: kt-no-exception-as-outcome (2026-10-09) — how a code-mode turn learns that an upstream round ended without an
// outcome, and hands that ending to the head as the round's result.
//
// Code mode's machinery is written over TurnOutcome: a round that posts, a source that streams on past the turn that
// started it, a runtime that is advanced across client steps. An upstream ending (a refusal after retries, no
// credentials, a tear that could not be re-issued, an oversized frame) is none of its outcomes. It used to leave as a
// thrown exception through all of that, and the broad RuntimeException catches around the cell could take it for a
// runtime fault. It is recorded here instead, once per intercepted turn, by whichever layer met it, and the machinery
// continues on an ordinary failed outcome that nothing in it can mistake for success. [CodeModeRoundInterceptor] reads
// the slot when the turn returns and answers with the ending itself, so the head writes the terminal for it.
package splice.provider.codex.stream

import kotlinx.coroutines.currentCoroutineContext
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.upstream.transport.StreamTornBeforeClient
import splice.upstream.transport.UpstreamEnding
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object CodeModeEndingKey : CoroutineContext.Key<CodeModeEndingSlot>

/** One intercepted turn's record of the first ending an upstream round gave it. */
internal class CodeModeEndingSlot : AbstractCoroutineContextElement(CodeModeEndingKey) {
    @Volatile
    var ending: UpstreamEnding? = null
        private set

    fun record(ending: UpstreamEnding) {
        if (this.ending == null) this.ending = ending
    }
}

internal object CodeModeEndings {
    /** The failed outcome the machinery continues on while the turn's slot holds the real ending. It is never
     *  written to a client: the interceptor answers with the ending. */
    suspend fun settle(ending: UpstreamEnding): TurnOutcome.Failure {
        currentCoroutineContext()[CodeModeEndingKey]?.record(ending)
        return placeholder()
    }

    /** The tear this turn has ended on, when that is how it ended; the cause carries what the head reads. */
    suspend fun tornCause(): IOException? =
        (currentCoroutineContext()[CodeModeEndingKey]?.ending as? StreamTornBeforeClient)?.cause

    fun placeholder(): TurnOutcome.Failure = TurnOutcome.Failure(
        "upstream ended the round before its outcome; the turn ends on the ending",
        cause = FailureCause.INTERNAL,
        phase = FailurePhase.MID_OUTPUT,
    )
}
