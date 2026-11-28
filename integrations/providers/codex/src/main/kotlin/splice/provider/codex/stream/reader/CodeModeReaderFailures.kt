// NEW: classifies thrown reader failures separately from parsed upstream outcomes and local persistence failures.
package splice.provider.codex.stream.reader

import kotlinx.coroutines.CancellationException
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.PersistedCause
import splice.core.failure.Position
import splice.core.failure.Tear
import splice.core.turn.RoundText
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.stream.source.CodeModeDeliveredText
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
    fun outcome(
        error: Throwable? = null,
        admitted: Boolean = false,
        partial: TurnOutcome.PartialRound? = null,
    ): TurnOutcome.Failure = TurnOutcome.Failure(
        cause = if (error is IOException && admitted) {
            FailureCause.Path.Torn(Tear.RESET, toolOpen = partial?.toolTearOpen == true)
        } else {
            FailureCause.Splice.Internal()
        },
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(SOURCE_FAILED),
        partial = partial,
    )

    /** Confirmed prose and tool-delivery state before a torn source reader threw, so re-anchoring respects both. */
    fun deliveredPartial(interrupted: Boolean, delivered: CodeModeDeliveredText?): TurnOutcome.PartialRound? {
        if (!interrupted || delivered == null) return null
        val text = delivered.text.orEmpty()
        if (text.isEmpty() && !hasToolActivity(delivered.hasToolUse, delivered.toolTearOpen)) return null
        return TurnOutcome.PartialRound(
            text = RoundText(bodyText = text, emittedText = text.isNotEmpty()),
            hasToolUse = delivered.hasToolUse,
            toolTearOpen = delivered.toolTearOpen,
        )
    }

    /** Whether [outcome] ended on a source that broke off: a reset, a truncation or a stall. */
    fun tornSource(outcome: TurnOutcome): Boolean =
        (outcome as? TurnOutcome.Failure)?.cause?.persisted in SOURCE_TEAR_CAUSES

    /** [failure] as an interrupted source ends the turn: a reset connection, after content. An unreachable path keeps
     *  its own cause, and with it the conn-reset tag its row has always carried. Client-visible prose and tool state
     *  (including gateway early blocks from [delivered]) survive so re-anchoring and salvage respect both; an empty
     *  partial stays null so an unstarted source does not replay. */
    fun interrupted(
        failure: TurnOutcome.Failure,
        delivered: CodeModeDeliveredText? = null,
    ): TurnOutcome.Failure {
        val partial = interruptedPartial(failure.partial, delivered)
        val toolOpen = partial?.toolTearOpen == true || delivered?.toolTearOpen == true
        return failure.copy(
            cause = failure.cause as? FailureCause.Path.Unreachable
                ?: FailureCause.Path.Torn(Tear.RESET, toolOpen = toolOpen),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            partial = partial,
        )
    }

    private fun interruptedPartial(
        partial: TurnOutcome.PartialRound?,
        delivered: CodeModeDeliveredText?,
    ): TurnOutcome.PartialRound? {
        if (partial == null) return deliveredPartial(interrupted = true, delivered = delivered)
        val hasTool = partial.hasToolUse || delivered?.hasToolUse == true
        val tearOpen = partial.toolTearOpen || delivered?.toolTearOpen == true
        return partial.copy(hasToolUse = hasTool, toolTearOpen = tearOpen)
            .takeIf { it.text.bodyText.isNotEmpty() || hasToolActivity(hasTool, tearOpen) }
    }

    private fun hasToolActivity(hasToolUse: Boolean, toolTearOpen: Boolean): Boolean = hasToolUse || toolTearOpen
}
