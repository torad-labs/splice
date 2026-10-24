// NEW: classifies thrown reader failures separately from parsed upstream outcomes and local persistence failures.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModePersistenceException
import java.io.IOException

internal object CodeModeReaderFailures {
    const val SOURCE_FAILED = "upstream source failed; source was not rerun"

    fun unexpected(cause: Throwable?): Throwable? = cause?.takeUnless {
        it is CancellationException || it is CodeModePersistenceException
    }

    /** A raw pre-admission seam exception is internal; an admitted reader's I/O failure is a transport reset. */
    fun outcome(error: Throwable? = null, admitted: Boolean = false): TurnOutcome.Failure = TurnOutcome.Failure(
        SOURCE_FAILED,
        cause = if (error is IOException && admitted) {
            FailureCause.UPSTREAM_CONN_RESET
        } else {
            FailureCause.INTERNAL
        },
        phase = FailurePhase.MID_OUTPUT,
    )
}
