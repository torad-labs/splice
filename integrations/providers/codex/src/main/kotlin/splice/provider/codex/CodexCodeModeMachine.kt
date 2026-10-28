// NEW: advances one live JavaScript cell while exposing only ordinary client tool calls.
package splice.provider.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import splice.core.turn.TurnOutcome
import splice.provider.codex.callbacks.CodeModeCallIssuanceGuard
import splice.provider.codex.callbacks.CodeModeCallIssuerEmitter
import splice.provider.codex.callbacks.CodeModeCallIssuerPoison
import splice.provider.codex.callbacks.CodeModeCallIssuerRetry
import splice.provider.codex.callbacks.CodexCodeModeCallIssuer
import splice.provider.codex.callbacks.CodexCodeModeCallIssuerActions
import splice.provider.codex.callbacks.CodexCodeModeIncrementalOutcomes
import splice.provider.codex.stream.CodeModeClientCalls
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.CodeModeLocalOutcome
import splice.provider.codex.stream.CodeModeNativeObservation
import splice.provider.codex.stream.CodeModeNativeSteps
import splice.provider.codex.stream.CodeModeNativeWallTime
import splice.provider.codex.stream.CodeModeOversizedCalls
import splice.provider.codex.stream.CodeModeRecoveryHistory
import splice.provider.codex.stream.CodeModeRejection
import splice.provider.codex.stream.CodeModeRuntimeFailures
import splice.provider.codex.stream.CodeModeSourceInterruptedException
import splice.provider.codex.stream.CodeModeStreamingCell
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import splice.upstream.failure.CodeModeToolException
import splice.upstream.sse.WireSink
import java.io.IOException

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
    private val nativeSteps = CodeModeNativeSteps(registry, CodeModeNativeWallTime(::wallMillis))
    private val failures = CodeModeRuntimeFailures(registry)

    private val oversized = CodeModeOversizedCalls(config, validation)
    private val callIssuer = CodexCodeModeCallIssuer(
        config = config,
        registry = registry,
        validation = validation,
        oversized = oversized,
        actions = CodexCodeModeCallIssuerActions(
            guard = CodeModeCallIssuanceGuard { record, cell -> checkIssuable(record, cell) },
            emitter = CodeModeCallIssuerEmitter { record, calls, sink, cell, recovery ->
                emit(record, calls, sink, cell, recovery)
            },
            poison = CodeModeCallIssuerPoison { record, message -> poison(record, message) },
            retry = CodeModeCallIssuerRetry { request, cell, results ->
                dispatchStep(request, cell, cell.advance(results))
            },
        ),
    )

    suspend fun advance(request: CodeModeAdvanceRequest): TurnOutcome {
        val record = request.record
        val source = request.source
        val now = config.clock.millis()
        registry.changes.edit(record) { it.stampCellStart(now) }
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
                        record.cellStartedAt = null
                        source.outcome()
                    }
                    // The round closed this live cell and removed it before the step could take it.
                    source?.closedLiveCell == true ->
                        failures.ioFailure(record, CodeModeSourceInterruptedException(source.permanentEnding))
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
            return CodeModeRejection.state("code-mode has no client calls to emit")
        }
        if (previous == null) {
            val streaming = cell as? CodeModeStreamingCell
            val sourceText = recovery?.text(streaming) ?: streaming?.deliveredText
            val observation = cell?.let(nativeSteps::observation)
            val progress = observation?.deliveredText().orEmpty()
            val text = (sourceText.orEmpty() + progress).takeIf(String::isNotEmpty) ?: sourceText
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
        cell?.let(nativeSteps::observation)?.delivered()
        return replay(served, sink, cell)
    }

    /** Emit the prior recorded calls without touching the runtime's consumed cursor. */
    suspend fun replay(calls: List<CodeModePending>, sink: WireSink, cell: CodeModeCell? = null): TurnOutcome {
        CodeModeClientCalls.emit(calls, sink, cell)
        return CodeModeLocalOutcome.success(hasToolUse = true)
    }

    fun interrupt(record: CodeModeRecord, detail: String = "additional client content arrived"): TurnOutcome {
        val output = CodeModeExecOutput.terminated(record, detail, wallMillis(record), config.bounds.maxOutputChars)
        registry.complete(record, output)
        return CodeModeLocalOutcome.success()
    }

    /** The script's wall time so far; its stamp goes with it, since the record is terminal after this. */
    private fun wallMillis(record: CodeModeRecord): Long {
        val now = config.clock.millis()
        val began = record.cellStartedAt ?: now
        record.cellStartedAt = null
        return now - began
    }

    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure = failures.poison(record, message)

    fun lostMessage(record: CodeModeRecord): String = failures.lostMessage(record)

    private suspend fun advanceCell(request: CodeModeAdvanceRequest, cell: CodeModeCell): TurnOutcome = try {
        registry.changes.edit(request.record) { it.progress.rounds++ }
        dispatchStep(request, cell, cell.advance(request.results))
    } catch (error: CancellationException) {
        cancelled(request.record, cell, error)
        throw error
    } catch (error: CodeModePersistenceException) {
        throw error
    } catch (error: CodeModeToolException) {
        if (error.preserveCell) nativeSteps.observation(cell)?.handOff()
        registry.complete(request.record, error.diagnostic)
        CodeModeLocalOutcome.success()
    } catch (error: IOException) {
        failures.ioFailure(request.record, error)
    } catch (error: IllegalStateException) {
        poison(request.record, failures.runtimeFailure(error, request.record))
    } catch (error: IllegalArgumentException) {
        poison(request.record, failures.runtimeFailure(error, request.record))
    } catch (_: RuntimeException) {
        poison(
            request.record,
            "code-mode runtime failed: RuntimeException: message withheld; " +
                "accepted results=${request.record.results.size}; source was not rerun",
        )
    }

    private suspend fun cancelled(record: CodeModeRecord, cell: CodeModeCell, cancellation: CancellationException) {
        record.cellStartedAt = null
        val native = nativeSteps.observation(cell)
        if (native == null) {
            registry.lose(record, "code-mode cell cancelled: ${lostMessage(record)}", cancellation)
            return
        }
        withContext(NonCancellable) {
            try {
                native.yieldObservation()
            } catch (error: CancellationException) {
                // Keep the caller's original cancellation even if yielding itself was interrupted.
                failedYield(record, cancellation, error)
            } catch (error: IOException) {
                failedYield(record, cancellation, error)
            } catch (error: IllegalStateException) {
                failedYield(record, cancellation, error)
            } catch (error: IllegalArgumentException) {
                failedYield(record, cancellation, error)
            }
        }
    }

    private fun failedYield(record: CodeModeRecord, cancellation: CancellationException, error: Exception) {
        cancellation.addSuppressed(error)
        registry.lose(record, "code-mode observation could not be yielded", cancellation)
    }

    private suspend fun dispatchStep(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        step: CodeModeStep,
    ): TurnOutcome {
        var observed = step
        while (observed is CodeModeStep.Notified) {
            nativeSteps.notify(request, cell, observed)
            observed = cell.advance()
        }
        return dispatchObserved(request, cell, observed).also { request.source?.billing?.prepare(it) }
    }

    private suspend fun dispatchObserved(
        request: CodeModeAdvanceRequest,
        cell: CodeModeCell,
        step: CodeModeStep,
    ): TurnOutcome = when (step) {
        is CodeModeStep.Calls -> callIssuer.accept(request, cell, step)
        is CodeModeStep.WaitingForResults ->
            CodexCodeModeIncrementalOutcomes.waitingForResults(registry, request.record, step)
        is CodeModeStep.Notified -> error("Native notification escaped its delivery loop")
        is CodeModeStep.Yielded -> nativeSteps.yielded(request, cell, step)
        is CodeModeStep.Completed -> complete(request.record, step, nativeSteps.observation(cell))
    }

    private fun checkIssuable(record: CodeModeRecord, cell: CodeModeCell?) {
        val streaming = cell as? CodeModeStreamingCell ?: return
        streaming.checkSource()
        check(record.phase != CodeModePhase.LOST) { "code-mode source is lost; source was not rerun" }
    }

    private fun complete(
        record: CodeModeRecord,
        step: CodeModeStep.Completed,
        observation: CodeModeNativeObservation?,
    ): TurnOutcome {
        if (observation != null) return nativeSteps.output(record, step, observation)
        if (!validation.fitsOutput(step.output)) return poison(record, "code-mode output exceeds the size limit")
        val wall = wallMillis(record)
        val output = step.error?.let { CodeModeExecOutput.failed(step.output, it, wall, config.bounds.maxOutputChars) }
            ?: CodeModeExecOutput.completed(step.output, wall, config.bounds.maxOutputChars)
        registry.complete(record, output)
        return CodeModeLocalOutcome.success()
    }
}
