// NEW: advances one live JavaScript cell while exposing only ordinary client tool calls.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.spi.CodeModeCall
import splice.spi.CodeModeCell
import splice.spi.CodeModeInfrastructureException
import splice.spi.CodeModeResult
import splice.spi.CodeModeStep
import splice.spi.CodeModeTimeoutException
import splice.spi.WireSink
import java.io.IOException
import java.util.UUID

private data class CodeModeAdvanceRequest(
    val record: CodeModeRecord,
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val results: List<CodeModeResult>,
    val sink: WireSink,
)

internal class CodexCodeModeMachine(
    private val config: CodeModeBridgeConfig,
    private val registry: CodexCodeModeRegistry,
    private val validation: CodexCodeModeValidation,
) {
    suspend fun advance(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
        disableParallel: Boolean,
        results: List<CodeModeResult>,
        sink: WireSink,
    ): TurnOutcome {
        val request = CodeModeAdvanceRequest(record, turn, disableParallel, results, sink)
        return when {
            record.rounds >= config.maxRounds -> poison(record, "code-mode round limit exceeded")
            else -> registry.cell(record)?.let { advanceCell(request, it) }
                ?: poison(record, "code-mode cell is unavailable: ${lostMessage(record)}")
        }
    }

    suspend fun emit(calls: List<CodeModePending>, sink: WireSink): TurnOutcome {
        if (calls.isEmpty()) return TurnOutcome.Failure(ErrorType.API_ERROR, "code-mode has no client calls to emit")
        calls.forEach { call ->
            val index = sink.openTool(call.clientId, call.name)
            sink.inputJsonDelta(index, call.arguments.toString())
            sink.closeBlock(index)
        }
        return TurnOutcome.Success(hasToolUse = true, incomplete = false, usage = Usage())
    }

    fun interrupt(record: CodeModeRecord, detail: String = "additional client content arrived"): TurnOutcome {
        val output = CodeModeInterruption.output(record, detail)
        if (!validation.fitsOutput(output)) {
            return poison(record, "code-mode interruption evidence exceeds the size limit; source was not rerun")
        }
        registry.complete(record, output)
        return TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage())
    }

    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure {
        registry.lose(record, message)
        return TurnOutcome.Failure(ErrorType.API_ERROR, message)
    }

    fun lostMessage(record: CodeModeRecord): String =
        "completed client call ids=${record.results.keys}; source was not rerun"

    private suspend fun advanceCell(request: CodeModeAdvanceRequest, cell: CodeModeCell): TurnOutcome = try {
        request.record.rounds++
        when (val step = cell.advance(request.results)) {
            is CodeModeStep.Calls -> acceptCalls(request, step.calls)
            is CodeModeStep.Completed -> complete(request.record, step)
        }
    } catch (error: CancellationException) {
        registry.lose(request.record, "code-mode cell cancelled: ${lostMessage(request.record)}", error)
        throw error
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (_: CodeModeTimeoutException) {
        poison(request.record, "code-mode runtime timed out; ${lostMessage(request.record)}")
    } catch (error: CodeModeInfrastructureException) {
        poison(
            request.record,
            "code-mode infrastructure failure ${error.category}/${error.faultClass}; ${lostMessage(request.record)}",
        )
    } catch (_: IOException) {
        poison(request.record, "code-mode runtime failed; ${lostMessage(request.record)}")
    } catch (_: RuntimeException) {
        poison(request.record, "code-mode runtime failed; ${lostMessage(request.record)}")
    }

    private suspend fun acceptCalls(
        request: CodeModeAdvanceRequest,
        calls: List<CodeModeCall>,
    ): TurnOutcome {
        validation.calls(request.record, request.turn.tools, calls)?.let { return poison(request.record, it) }
        request.record.totalCalls += calls.size
        request.record.pending += calls.mapIndexed { index, call ->
            CodeModePending(
                runtimeId = call.id,
                clientId = "$CODE_MODE_CLIENT_ID_PREFIX${UUID.randomUUID()}",
                name = call.name,
                arguments = call.arguments,
                exposed = !request.disableParallel || index == 0,
            )
        }
        request.record.updatedAt = config.clock.millis()
        registry.save()
        return emit(request.record.visiblePending(), request.sink)
    }

    private fun complete(record: CodeModeRecord, step: CodeModeStep.Completed): TurnOutcome {
        val output = step.error?.let { "code-mode error: $it" } ?: step.output
        if (!validation.fitsOutput(output)) return poison(record, "code-mode output exceeds the size limit")
        registry.complete(record, output)
        return TurnOutcome.Success(false, false, Usage())
    }
}

internal const val CODE_MODE_CLIENT_ID_PREFIX = "toolu_splice_"
