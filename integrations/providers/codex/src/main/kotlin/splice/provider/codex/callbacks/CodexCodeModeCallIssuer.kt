package splice.provider.codex.callbacks

import splice.core.turn.TurnOutcome
import splice.provider.codex.CODE_MODE_CLIENT_ID_PREFIX
import splice.provider.codex.CodeModeAdvanceRequest
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePending
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.CodexCodeModeValidation
import splice.provider.codex.state.CodeModeWeight
import splice.provider.codex.stream.CodeModeOversizedCalls
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.sse.WireSink
import java.util.UUID

internal fun interface CodeModeCallIssuanceGuard {
    fun check(record: CodeModeRecord, cell: CodeModeCell?)
}

internal fun interface CodeModeCallIssuerEmitter {
    suspend fun emit(
        record: CodeModeRecord,
        calls: List<CodeModePending>,
        sink: WireSink,
        cell: CodeModeCell?,
        recovery: CodeModeRecoveryHistory.Delivery?,
    ): TurnOutcome
}

internal fun interface CodeModeCallIssuerPoison {
    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure
}

internal fun interface CodeModeCallIssuerRetry {
    suspend fun retry(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        results: List<CodeModeResult>,
    ): TurnOutcome
}

/** Persists and exposes one validated callback wave from the running worker. */
internal data class CodexCodeModeCallIssuerActions(
    val guard: CodeModeCallIssuanceGuard,
    val emitter: CodeModeCallIssuerEmitter,
    val poison: CodeModeCallIssuerPoison,
    val retry: CodeModeCallIssuerRetry,
)

internal class CodexCodeModeCallIssuer(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val validation: CodexCodeModeValidation,
    private val oversized: CodeModeOversizedCalls,
    private val actions: CodexCodeModeCallIssuerActions,
) {
    suspend fun accept(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        step: CodeModeStep.Calls,
    ): TurnOutcome {
        val calls = step.calls
        CodexCodeModeIncrementalOutcomes.validateCalls(validation, request, step)
            ?.let { return actions.poison.poison(request.record, it) }
        // The empty answer is a second oversized batch from the same script, which ends it (CodeModeOversizedCalls).
        oversized.refused(request.record, calls)?.let { refused ->
            CodexCodeModeIncrementalOutcomes.saveOutput(registry, request.record, step)
            return if (refused.isEmpty()) {
                actions.poison.poison(request.record, OVERSIZED_AGAIN)
            } else {
                actions.retry.retry(request, cell, refused)
            }
        }
        val pending = calls.mapIndexed { index, call ->
            CodeModePending(
                runtimeId = call.id,
                clientId = "$CODE_MODE_CLIENT_ID_PREFIX${UUID.randomUUID()}",
                name = call.name,
                arguments = call.arguments,
                exposed = !request.disableParallel || index == 0,
            )
        }
        registry.changes.edit(
            request.record,
            growthBytes = pending.sumOf(CodeModeWeight.STORED::call),
        ) { record ->
            actions.guard.check(record, cell)
            record.progress.totalCalls += calls.size
            record.progress.pending += pending
            record.progress.updatedAt = config.clock.millis()
        }
        // The worker already took this wave: a failed output save keeps it pending for a retry to re-emit.
        CodexCodeModeIncrementalOutcomes.saveOutput(registry, request.record, step)
        // Existing pending calls may still be waiting on the client while this partial result made
        // a new dependent callback ready. Emit only this newly issued wave; replaying the whole
        // visible set would execute the siblings twice in the client.
        return actions.emitter.emit(
            request.record,
            pending.filter(CodeModePending::exposed),
            request.sink,
            cell,
            request.recovery,
        )
    }
}

private const val OVERSIZED_AGAIN: String = "code-mode tool arguments exceed the size limit again after one refusal"
