// NEW: advances one live JavaScript cell while exposing only ordinary client tool calls.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.FailureTraits
import splice.core.turn.TurnOutcome
import splice.core.turn.UsageField
import splice.core.turn.noRequestUsage
import splice.core.util.JsonWire
import splice.core.util.SafeFailureText
import splice.provider.codex.state.CodeModeWorkerRecovery
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeRecoveryHistory
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

/** One step of a running script: the results its calls returned, where the step writes, and the live round and
 *  recovery it came from, when it came from one. */
internal data class CodeModeAdvanceRequest(
    val record: CodeModeRecord,
    val turn: CodexCodeModeBridge.Turn,
    val disableParallel: Boolean,
    val results: List<CodeModeResult>,
    val sink: WireSink,
    val source: CodeModeLiveRound? = null,
    val recovery: CodeModeRecoveryHistory.Delivery? = null,
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

    suspend fun advance(request: CodeModeAdvanceRequest): TurnOutcome {
        val record = request.record
        val source = request.source
        started.putIfAbsent(record.id, config.clock.millis())
        return when {
            config.bounds.maxRounds?.let { record.progress.rounds >= it } == true ->
                poison(record, "code-mode round limit exceeded")
            else -> registry.retainedCells.acquire(record)?.let { cell ->
                try {
                    advanceCell(request, cell)
                } finally {
                    registry.retainedCells.release(record)
                }
            }
                ?: when {
                    source?.sourceInterrupted == true -> {
                        started.remove(record.id)
                        source.outcome()
                    }
                    // The round closed this live cell and removed it before the step could take it.
                    source?.closedLiveCell == true ->
                        ioFailure(record, CodeModeSourceInterruptedException(source.permanentEnding))
                    else -> poison(record, "code-mode cell is unavailable: ${lostMessage(record)}")
                }
        }
    }

    suspend fun emit(
        record: CodeModeRecord,
        calls: List<CodeModePending>,
        sink: WireSink,
        cell: CodeModeCell? = null,
        recovery: CodeModeRecoveryHistory.Delivery? = null,
    ): TurnOutcome {
        registry.changes.edit(record) { checkIssuable(it, cell) }
        val previous = record.issued.firstOrNull { it.requestDigest == record.progress.lastDigest }
        val served = previous?.calls ?: calls
        if (served.isEmpty()) {
            return TurnOutcome.Failure(
                "code-mode has no client calls to emit",
                traits = FailureTraits(deterministic = true),
                salvagedUsage = noRequestUsage,
                cause = FailureCause.CODE_MODE_PROTOCOL,
                phase = FailurePhase.MID_OUTPUT,
            )
        }
        if (previous == null) {
            val streaming = cell as? CodeModeStreamingCell
            val text = recovery?.text(streaming) ?: streaming?.deliveredText
            val native = streaming?.deliveredNative
            val issued = CodeModeIssuedStep(record.progress.lastDigest, calls.map(CodeModePending::copy), text, native)
            // A failed save: the worker already advanced, but no callback reached the client. A retry
            // reuses persisted pending ids and earns this issuance with a successful save.
            registry.changes.save(
                record,
                undo = { it.issued.remove(issued) },
                growthBytes = calls.sumOf(splice.provider.codex.state.CodeModeWeight.STORED::call) +
                    splice.provider.codex.state.CodeModeWeight.STORED.delivered(issued),
            ) {
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
            sink.inputJsonDelta(index, JsonWire.string(call.arguments))
            sink.closeBlock(index)
        }
        return TurnOutcome.Success(
            hasToolUse = true,
            incomplete = false,
            usage = noRequestUsage.copy(
                origin = noRequestUsage.origin.copy(localStep = true),
                reported = UsageField.entries.toSet(),
            ),
        )
    }

    fun interrupt(record: CodeModeRecord, detail: String = "additional client content arrived"): TurnOutcome {
        val output = CodeModeExecOutput.terminated(record, detail, wallMillis(record), config.bounds.maxOutputChars)
        registry.complete(record, output)
        return TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = noRequestUsage.copy(
                origin = noRequestUsage.origin.copy(localStep = true),
                reported = UsageField.entries.toSet(),
            ),
        )
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
            traits = FailureTraits(deterministic = true),
            salvagedUsage = noRequestUsage,
            cause = FailureCause.CODE_MODE_PROTOCOL,
            phase = FailurePhase.MID_OUTPUT,
        )
    }

    fun lostMessage(record: CodeModeRecord): String =
        "completed client call ids=${record.results.keys}; source was not rerun"

    private suspend fun advanceCell(request: CodeModeAdvanceRequest, cell: CodeModeCell): TurnOutcome = try {
        registry.changes.edit(request.record) { it.progress.rounds++ }
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
            error.verdict ?: TurnOutcome.Failure(
                message,
                cause = FailureCause.UPSTREAM_CONN_RESET,
                phase = FailurePhase.MID_OUTPUT,
            )
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
        }.also { request.source?.billing?.prepare(it) }

    /** Throwable messages may quote script or tool bytes. Only audited operational text is safe
     * for daemon.log; every other message keeps its concrete exception class and splice throw site
     * but not its content. */
    private fun runtimeFailure(error: Throwable, record: CodeModeRecord): String {
        val firstLine = error.message?.lineSequence()?.firstOrNull()?.trim()
        val detail = if (firstLine == "worker pool exhausted") firstLine else "message withheld"
        return "code-mode runtime failed: ${error::class.simpleName}: $detail${SafeFailureText.site(error)}; " +
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
        registry.changes.edit(
            request.record,
            growthBytes = pending.sumOf(splice.provider.codex.state.CodeModeWeight.STORED::call),
        ) { record ->
            checkIssuable(record, cell)
            record.progress.totalCalls += calls.size
            record.progress.pending += pending
            record.progress.updatedAt = config.clock.millis()
        }
        return emit(request.record, request.record.visiblePending(), request.sink, cell, request.recovery)
    }

    private fun complete(record: CodeModeRecord, step: CodeModeStep.Completed): TurnOutcome {
        if (!validation.fitsOutput(step.output)) return poison(record, "code-mode output exceeds the size limit")
        val wall = wallMillis(record)
        val output = step.error?.let { CodeModeExecOutput.failed(step.output, it, wall, config.bounds.maxOutputChars) }
            ?: CodeModeExecOutput.completed(step.output, wall, config.bounds.maxOutputChars)
        registry.complete(record, output)
        return TurnOutcome.Success(
            hasToolUse = false,
            incomplete = false,
            usage = noRequestUsage.copy(
                origin = noRequestUsage.origin.copy(localStep = true),
                reported = UsageField.entries.toSet(),
            ),
        )
    }
}

internal const val CODE_MODE_CLIENT_ID_PREFIX = "toolu_splice_"
