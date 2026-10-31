package splice.provider.codex.callbacks

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CodeModeAdvanceRequest
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.upstream.codemode.CodeModeStep

internal object CodexCodeModeIncrementalOutcomes {
    fun waitingForResults(
        registry: CodexCodeModeRegistry,
        record: CodeModeRecord,
        step: CodeModeStep.WaitingForResults,
    ): TurnOutcome.Failure {
        if (step.output != null) {
            registry.changes.save(record) { live ->
                live.progress.output = step.output
                live.progress.completionError = step.error
            }
        }
        return TurnOutcome.Failure(
            // Not a verdict on the recorded state: the outstanding results may still arrive.
            cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_START),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence("Code-mode is waiting for outstanding client tool results"),
            salvagedUsage = noRequestUsage,
        )
    }

    fun validateCalls(
        validation: CodexCodeModeValidation,
        request: CodeModeAdvanceRequest,
        step: CodeModeStep.Calls,
    ): String? {
        val output = step.output
        if (output != null && !validation.fitsOutput(output)) return "code-mode output exceeds the size limit"
        return validation.calls(request.record, request.turn.tools, step.calls)
    }

    fun saveOutput(registry: CodexCodeModeRegistry, record: CodeModeRecord, step: CodeModeStep.Calls) {
        val output = step.output ?: return
        registry.changes.save(record) { live ->
            live.progress.output = output
            live.progress.completionError = step.error
        }
    }
}
