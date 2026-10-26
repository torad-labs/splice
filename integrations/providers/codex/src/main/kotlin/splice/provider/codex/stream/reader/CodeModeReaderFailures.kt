// NEW: classifies thrown reader failures separately from parsed upstream outcomes and local persistence failures.
package splice.provider.codex.stream.reader

import kotlinx.coroutines.CancellationException
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.PersistedCause
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModePersistenceException
import java.io.IOException

internal object CodeModeReaderFailures {
    const val SOURCE_FAILED = "upstream source failed; source was not rerun"

    // why: a disposed execution cannot retain its response reader or dispatch unread source.
    const val SOURCE_DISPOSED = "code-mode execution disposed; source was not rerun"
    const val HEAD_STOPPED = "code-mode head stopped"

    /** The endings that mean the source broke off rather than answered. */
    private val SOURCE_TEAR_CAUSES = setOf(
        PersistedCause.UPSTREAM_CONN_RESET,
        PersistedCause.UPSTREAM_TRUNCATED,
        PersistedCause.UPSTREAM_STALLED,
    )

    fun unexpected(cause: Throwable?): Throwable? = cause?.takeUnless {
        it is CancellationException || it is CodeModePersistenceException
    }

    /** A raw pre-admission seam exception is internal; an admitted reader's I/O failure is a transport reset. */
    fun outcome(error: Throwable? = null, admitted: Boolean = false): TurnOutcome.Failure = TurnOutcome.Failure(
        cause = if (error is IOException && admitted) {
            FailureCause.Path.Torn(Tear.RESET, toolOpen = false)
        } else {
            FailureCause.Splice.Internal()
        },
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(SOURCE_FAILED),
    )

    /** Whether [outcome] ended on a source that broke off: a reset, a truncation or a stall. */
    fun tornSource(outcome: TurnOutcome): Boolean =
        (outcome as? TurnOutcome.Failure)?.cause?.persisted in SOURCE_TEAR_CAUSES

    /** [failure] as an interrupted source ends the turn: a reset connection, after content. An unreachable path keeps
     *  its own cause, and with it the conn-reset tag its row has always carried. */
    fun interrupted(failure: TurnOutcome.Failure): TurnOutcome.Failure = failure.copy(
        cause = failure.cause as? FailureCause.Path.Unreachable ?: FailureCause.Path.Torn(Tear.RESET, toolOpen = false),
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        partial = null,
    )
}
