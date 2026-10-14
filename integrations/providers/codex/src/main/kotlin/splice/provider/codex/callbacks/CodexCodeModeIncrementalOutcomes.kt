package splice.provider.codex.callbacks

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
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
            message = "Code-mode is waiting for outstanding client tool results",
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = noRequestUsage,
        )
    }

    fun validateAndSaveCalls(
        registry: CodexCodeModeRegistry,
        validation: CodexCodeModeValidation,
        request: CodeModeAdvanceRequest,
        step: CodeModeStep.Calls,
    ): String? {
        val record = request.record
        val output = step.output
        if (output != null && !validation.fitsOutput(output)) return "code-mode output exceeds the size limit"
        validation.calls(record, request.turn.tools, step.calls)?.let { return it }
        if (output != null) {
            registry.changes.save(record) { live ->
                live.progress.output = output
                live.progress.completionError = step.error
            }
        }
        return null
    }
}
