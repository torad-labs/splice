// NEW: a local worker cut preserves completed evidence and reaches the client's existing retry path.
package splice.provider.codex.state

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry

internal class CodeModeWorkerRecovery(private val registry: CodexCodeModeRegistry) {
    fun lost(record: CodeModeRecord): TurnOutcome.Failure {
        val detail = "code-mode worker was stopped; accepted results=${record.results.size}; source was not rerun"
        registry.lose(record, detail)
        return TurnOutcome.Failure(
            cause = FailureCause.Splice.Internal(),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence(detail),
            salvagedUsage = noRequestUsage,
        )
    }
}
