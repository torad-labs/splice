// NEW: local source rejection owns safe outcome construction and best-effort lost-record cleanup.
package splice.provider.codex.stream

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry

internal object CodeModeRejection {
    fun outcome(error: Throwable): TurnOutcome.Failure = if (error is CodeModePersistenceException) {
        error.outcome()
    } else {
        state(
            "splice code-mode source rejected (${error::class.simpleName}${SafeFailureText.site(error)}: " +
                "${SafeFailureText.render(error)}); source was not rerun",
        )
    }

    /** The turn ends because splice's own code-mode state cannot go on: a verdict the same request meets again,
     *  reported in [message] once the turn is streaming. */
    fun state(message: String): TurnOutcome.Failure = TurnOutcome.Failure(
        cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_STATE),
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(message),
        salvagedUsage = noRequestUsage,
    )

    fun lose(
        record: CodeModeRecord?,
        rejected: TurnOutcome.Failure,
        registry: CodexCodeModeRegistry,
    ): TurnOutcome.Failure {
        val cleanup = Cancellables.runCatchingBestEffort {
            record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, rejected.evidence.message) }
        }.exceptionOrNull() ?: return rejected
        return if (cleanup is CodeModePersistenceException) {
            cleanup.outcome()
        } else {
            rejected.copy(
                evidence = rejected.evidence.copy(
                    message = rejected.evidence.message + "; splice code-mode rejection cleanup failed " +
                        "(${SafeFailureText.render(cleanup)})",
                ),
            )
        }
    }
}
