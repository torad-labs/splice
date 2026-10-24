package splice.provider.codex.stream

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.TurnOutcome
import splice.core.turn.UsageField
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.provider.codex.CodeModeAdvanceRequest
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.callbacks.CodexCodeModeIncrementalOutcomes
import splice.provider.codex.state.CodeModeWeight
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeStep
import splice.upstream.codemode.NativeCodeModeOutput

internal class CodeModeNativeSteps(
    private val registry: CodexCodeModeRegistry,
    private val wall: CodeModeNativeWallTime,
) {
    fun observation(cell: CodeModeCell): CodeModeNativeObservation? =
        (cell as? CodeModeNativeObservation) ?: (cell as? CodeModeStreamingCell)?.nativeObservation

    suspend fun notify(request: CodeModeAdvanceRequest, cell: CodeModeCell, step: CodeModeStep.Notified) {
        if (step.text.isBlank()) return
        val receipt = ResponsesAssistantText.item(step.text, AssistantPhase.COMMENTARY)
        val callId = observation(cell)?.notificationCallId ?: request.record.origin.outerCallId
        val notification = buildJsonObject {
            put("type", "custom_tool_call_output")
            put("call_id", callId)
            put("name", "exec")
            put("output", step.text)
        }
        registry.changes.save(
            request.record,
            growthBytes = CodeModeWeight.STORED.json(receipt) + CodeModeWeight.STORED.json(notification),
        ) {
            it.deliveredOnly = it.deliveredOnly + receipt
            it.progress.nativeNotifications = it.progress.nativeNotifications + notification
        }
        observation(cell)?.notification(step.text)
        request.sink.addTextBlock(step.text)
    }

    fun yielded(request: CodeModeAdvanceRequest, cell: CodeModeCell, step: CodeModeStep.Yielded): TurnOutcome {
        val observation = checkNotNull(observation(cell)) { "runtime yielded without a native cell owner" }
        if (request.record.progress.pending.any { it.clientId !in request.record.results }) {
            observation.defer(step)
            return CodexCodeModeIncrementalOutcomes.waitingForResults(
                registry,
                request.record,
                CodeModeStep.WaitingForResults(null),
            )
        }
        return output(request.record, step, observation)
    }

    fun output(record: CodeModeRecord, step: CodeModeStep, observation: CodeModeNativeObservation): TurnOutcome {
        val content = NativeCodeModeOutput.content(
            step,
            wall.elapsed(record),
            observation.invocation.options.maxOutputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        val output = JsonArray(content)
        if (step is CodeModeStep.Yielded) observation.handOff()
        registry.complete(record, JsonWire.string(output), output)
        return CodeModeLocalOutcome.success()
    }
}

internal object CodeModeLocalOutcome {
    fun success(): TurnOutcome.Success = TurnOutcome.Success(
        hasToolUse = false,
        incomplete = false,
        usage = noRequestUsage.copy(
            origin = noRequestUsage.origin.copy(localStep = true),
            reported = UsageField.entries.toSet(),
        ),
    )
}

internal fun interface CodeModeNativeWallTime {
    fun elapsed(record: CodeModeRecord): Long
}
