// NEW: advances one live JavaScript cell while exposing only ordinary client tool calls.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.provider.codex.state.CodeModeWorkerRecovery
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeSourceInterruptedException
import splice.provider.codex.stream.CodeModeStreamingCell
import splice.upstream.codemode.CodeModeCall
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
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
    private val workerRecovery = CodeModeWorkerRecovery(registry)

    suspend fun advance(
        record: CodeModeRecord,
        turn: CodexCodeModeBridge.Turn,
        disableParallel: Boolean,
        results: List<CodeModeResult>,
        sink: WireSink,
        source: CodeModeLiveRound? = null,
    ): TurnOutcome {
        started.putIfAbsent(record.id, config.clock.millis())
        val request = CodeModeAdvanceRequest(record, turn, disableParallel, results, sink)
        return when {
            config.maxRounds?.let { record.rounds >= it } == true -> poison(record, "code-mode round limit exceeded")
            else -> registry.cell(record)?.let { advanceCell(request, it) }
                ?: if (source?.sourceInterrupted == true) {
                    source.outcome()
                } else {
                    poison(record, "code-mode cell is unavailable: ${lostMessage(record)}")
                }
        }
    }

    suspend fun emit(
        record: CodeModeRecord,
        calls: List<CodeModePending>,
        sink: WireSink,
        cell: CodeModeCell? = null,
    ): TurnOutcome {
        registry.changes.edit(record) { checkIssuable(it, cell) }
        val previous = record.issued.firstOrNull { it.requestDigest == record.lastDigest }
        val served = previous?.calls ?: calls
        if (served.isEmpty()) {
            return TurnOutcome.Failure(
                "code-mode has no client calls to emit",
                deterministic = true,
                cause = FailureCause.CODE_MODE_PROTOCOL,
                phase = FailurePhase.MID_OUTPUT,
            )
        }
        if (previous == null) {
            val issued = CodeModeIssuedStep(record.lastDigest, calls.map(CodeModePending::copy))
            // A failed save: the worker already advanced, but no callback reached the client. A retry
            // reuses persisted pending ids and earns this issuance with a successful save.
            registry.changes.save(record, undo = { it.issued.remove(issued) }) {
                checkIssuable(it, cell)
                it.issued += issued
            }
        }
        return replay(served, sink)
    }

    /** Emit the prior recorded calls without touching the runtime's consumed cursor. */
    suspend fun replay(calls: List<CodeModePending>, sink: WireSink): TurnOutcome {
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
        registry.changes.edit(request.record) { it.rounds++ }
        dispatchStep(request, cell, cell.advance(request.results))
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
    } catch (error: IOException) {
        ioFailure(request.record, error)
    } catch (error: IllegalStateException) {
        poison(request.record, runtimeFailure(error, request.record))
    } catch (error: IllegalArgumentException) {
        poison(request.record, runtimeFailure(error, request.record))
    } catch (_: RuntimeException) {
        poison(
            request.record,
            "code-mode runtime failed: RuntimeException: message withheld; " +
                "accepted results=${request.record.results.size}; source was not rerun",
        )
    }

    private fun ioFailure(record: CodeModeRecord, error: IOException): TurnOutcome.Failure = when (error) {
        is CodeModeSourceInterruptedException -> {
            started.remove(record.id)
            val message = "upstream code-mode source interrupted; ${lostMessage(record)}"
            registry.lose(record, message)
            TurnOutcome.Failure(message, cause = FailureCause.UPSTREAM_CONN_RESET, phase = FailurePhase.MID_OUTPUT)
        }
        is CodeModeWorkerLostException -> {
            started.remove(record.id)
            workerRecovery.lost(record)
        }
        else -> poison(record, runtimeFailure(error, record))
    }

    private suspend fun dispatchStep(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        step: CodeModeStep,
    ): TurnOutcome =
        when (step) {
            is CodeModeStep.Calls -> acceptCalls(request, cell, step.calls)
            is CodeModeStep.Completed -> complete(request.record, step)
        }

    /** Throwable messages may quote script or tool bytes. Only audited operational text is safe
     * for daemon.log; every other message keeps its concrete exception class but not its content. */
    private fun runtimeFailure(error: Throwable, record: CodeModeRecord): String {
        val firstLine = error.message?.lineSequence()?.firstOrNull()?.trim()
        val detail = if (firstLine == "worker pool exhausted") firstLine else "message withheld"
        return "code-mode runtime failed: ${error::class.simpleName}: $detail; " +
            "accepted results=${record.results.size}; source was not rerun"
    }

    private fun checkIssuable(record: CodeModeRecord, cell: CodeModeCell?) {
        val streaming = cell as? CodeModeStreamingCell ?: return
        streaming.checkSource()
        check(record.phase != CodeModePhase.LOST) { "code-mode source is lost; source was not rerun" }
    }

    private suspend fun acceptCalls(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        calls: List<CodeModeCall>,
    ): TurnOutcome {
        validation.calls(request.record, request.turn.tools, calls)?.let { return poison(request.record, it) }
        val pending = calls.mapIndexed { index, call ->
            CodeModePending(
                runtimeId = call.id,
                clientId = "$CODE_MODE_CLIENT_ID_PREFIX${UUID.randomUUID()}",
                name = call.name,
                arguments = call.arguments,
                exposed = !request.disableParallel || index == 0,
            )
        }
        registry.changes.save(request.record) { record ->
            checkIssuable(record, cell)
            record.totalCalls += calls.size
            record.pending += pending
            record.updatedAt = config.clock.millis()
        }
        return emit(request.record, request.record.visiblePending(), request.sink, cell)
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
