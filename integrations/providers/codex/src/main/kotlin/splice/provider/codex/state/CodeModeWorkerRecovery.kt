// NEW: a local worker cut preserves completed evidence and reaches the client's existing retry path.
package splice.provider.codex.state

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry

internal class CodeModeWorkerRecovery(private val registry: CodexCodeModeRegistry) {
    fun lost(record: CodeModeRecord): TurnOutcome.Failure {
        val detail = "code-mode worker was stopped; accepted results=${record.results.size}; source was not rerun"
        registry.lose(record, detail)
        return TurnOutcome.Failure(detail, cause = FailureCause.INTERNAL, phase = FailurePhase.MID_OUTPUT)
    }
}
