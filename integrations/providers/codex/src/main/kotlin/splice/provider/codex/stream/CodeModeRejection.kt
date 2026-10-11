// NEW: local source rejection owns safe outcome construction and best-effort lost-record cleanup.
package splice.provider.codex.stream

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.FailureTraits
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
        TurnOutcome.Failure(
            "splice code-mode source rejected (${SafeFailureText.render(error)}); source was not rerun",
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
            traits = FailureTraits(deterministic = true),
            salvagedUsage = noRequestUsage,
        )
    }

    fun lose(
        record: CodeModeRecord?,
        rejected: TurnOutcome.Failure,
        registry: CodexCodeModeRegistry,
    ): TurnOutcome.Failure {
        val cleanup = Cancellables.runCatchingBestEffort {
            record?.takeUnless(CodeModeRecord::terminal)?.let { registry.lose(it, rejected.message) }
        }.exceptionOrNull() ?: return rejected
        return if (cleanup is CodeModePersistenceException) {
            cleanup.outcome()
        } else {
            rejected.copy(
                message = rejected.message + "; splice code-mode rejection cleanup failed " +
                    "(${SafeFailureText.render(cleanup)})",
            )
        }
    }
}
