// NEW: advances one live JavaScript cell while exposing only ordinary client tool calls.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.sse.WireSink
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    /** When each running script first advanced, for codex's "Wall time" line. In memory only: a restored
     *  ACTIVE record comes back LOST, so a script completes in the daemon that started it or not at all. */
    private val started: MutableMap<String, Long> = ConcurrentHashMap()

    suspend fun advance(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
        disableParallel: Boolean,
        results: List<CodeModeResult>,
        sink: WireSink,
    ): TurnOutcome {
        started.putIfAbsent(record.id, config.clock.millis())
        val request = CodeModeAdvanceRequest(record, turn, disableParallel, results, sink)
        return when {
            record.rounds >= config.maxRounds -> poison(record, "code-mode round limit exceeded")
            else -> registry.cell(record)?.let { advanceCell(request, it) }
                ?: poison(record, "code-mode cell is unavailable: ${lostMessage(record)}")
        }
    }

    suspend fun emit(calls: List<CodeModePending>, sink: WireSink): TurnOutcome {
        if (calls.isEmpty()) {
            return TurnOutcome.Failure(
                "code-mode has no client calls to emit",
                deterministic = true,
                cause = FailureCause.CODE_MODE_PROTOCOL,
                phase = FailurePhase.MID_OUTPUT,
            )
        }
        calls.forEach { call ->
            val index = sink.openTool(call.clientId, call.name)
            sink.inputJsonDelta(index, call.arguments.toString())
            sink.closeBlock(index)
        }
        return TurnOutcome.Success(hasToolUse = true, incomplete = false, usage = Usage(localStep = true))
    }

    fun interrupt(record: CodeModeRecord, detail: String = "additional client content arrived"): TurnOutcome {
        val output = CodeModeExecOutput.terminated(record, detail, wallMillis(record), config.maxOutputChars)
        registry.complete(record, output)
        return TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(localStep = true))
    }

    /** The script's wall time so far; its stamp goes with it, since the record is terminal after this. */
    private fun wallMillis(record: CodeModeRecord): Long {
        val now = config.clock.millis()
        return now - (started.remove(record.id) ?: now)
    }

    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure {
        started.remove(record.id)
        registry.lose(record, message)
        return TurnOutcome.Failure(
            message,
            deterministic = true,
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
        )
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
        started.remove(request.record.id)
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
        if (!validation.fitsOutput(step.output)) return poison(record, "code-mode output exceeds the size limit")
        val wall = wallMillis(record)
        val output = step.error?.let { CodeModeExecOutput.failed(step.output, it, wall, config.maxOutputChars) }
            ?: CodeModeExecOutput.completed(step.output, wall, config.maxOutputChars)
        registry.complete(record, output)
        return TurnOutcome.Success(hasToolUse = false, incomplete = false, usage = Usage(localStep = true))
    }
}

internal const val CODE_MODE_CLIENT_ID_PREFIX = "toolu_splice_"
