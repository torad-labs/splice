// NEW: only a typed failed boot permits reopening a fresh source cursor from its durable beginning.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CODE_MODE_WAIT_TOOL_NAME
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSourcePart
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import java.io.IOException

internal class CodeModeRuntimeStarter(
    private val run: CodeModeRuntimeRun,
    private val registry: CodexCodeModeRegistry,
    private val config: CodeModeBridgeConfig,
) {
    suspend fun start(record: CodeModeRecord, context: CodeModeRunContext, stream: CodeModeLiveRound?): CodeModeCell =
        try {
            record.runtimeDispatching = true
            if (stream == null) {
                starting(record, context, null)
            } else {
                stream.executing(record)
                val cell = stream.sourceFor(record).whileStarting { starting(record, context, stream) }
                CodeModeStreamingCell(cell, record, stream)
            }
        } catch (error: IOException) {
            reportSourceRejection(context, stream)
            throw error
        }

    private fun reportSourceRejection(context: CodeModeRunContext, stream: CodeModeLiveRound?) {
        if (stream?.source?.startupRejected != true) return
        context.link.post.perf?.add(PerfKeys.CODE_MODE_START_REJECTED, 1)
        config.log("[code-mode] failed source released runtime startup; no cell adopted or source rerun")
    }

    private suspend fun starting(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
    ): CodeModeCell {
        while (true) {
            try {
                return open(record, context, stream)
            } catch (error: CodeModeCapacityException) {
                // Nothing was dispatched. Reclaim only cells already eligible for session/lifetime cleanup.
                if (registry.evictIdleCell() == null) {
                    registry.retainedCells.logRefusal()
                    throw error
                }
            }
        }
    }

    private suspend fun open(
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
    ): CodeModeCell {
        val host = runtime()
        if (host is NativeCodeModeLifecycle) return native(host, record, context, stream)
        check(record.origin.outer["name"]?.let { splice.core.util.JsonScalars.str(it) } != CODE_MODE_WAIT_TOOL_NAME) {
            "wait requires a native code-mode host"
        }
        return if (stream == null) {
            host.startSession(record.key, record.origin.source, context.turn.tools, context.turn.descriptions)
        } else {
            host.startStreamingSession(
                record.key,
                stream.sourceFor(record).view(),
                context.turn.tools,
                context.turn.descriptions,
            )
        }
    }

    private suspend fun native(
        host: NativeCodeModeLifecycle,
        record: CodeModeRecord,
        context: CodeModeRunContext,
        stream: CodeModeLiveRound?,
    ): CodeModeCell {
        record.nativeRuntime = true
        val owner = record.conversationId ?: splice.provider.codex.state.CodeModeTurnIdentity().conversationId(
            context.turn,
        )
        val source = if (stream == null) {
            record.origin.source
        } else {
            when (val whole = stream.sourceFor(record).view().read()) {
                is CodeModeSourcePart.Complete -> whole.text
                is CodeModeSourcePart.Failed -> throw IOException("Native code-mode source did not complete")
            }
        }
        val waiting = splice.core.util.JsonScalars.str(record.origin.outer, "name") == CODE_MODE_WAIT_TOOL_NAME
        val invocation = if (waiting) CodeModeNativeInvocations.wait(source) else CodeModeNativeInvocations.exec(source)
        val held = if (waiting) {
            retained(record, invocation, owner)
        } else {
            require(invocation.options.maxOutputTokens <= Int.MAX_VALUE.toLong()) {
                "Native exec output token budget exceeds the pinned host protocol range"
            }
            val cell = host.startNativeSession(
                owner,
                checkNotNull(invocation.source),
                context.turn.tools,
                context.turn.descriptions,
                invocation.options,
            )
            CodeModeHeldNativeCell(cell, record.origin.outerCallId)
        }
        registry.nativeCells.trackSession(record.key, owner, context.turn.sessionId)
        record.nativeCellId = held.cell.cellId
        return CodeModeNativeObservation(
            owner,
            held.cell,
            invocation,
            registry.nativeCells,
            held.notificationCallId,
        )
    }

    suspend fun select(record: CodeModeRecord, stream: CodeModeLiveRound?): CodeModeRecord {
        if (stream == null || runtime() !is NativeCodeModeLifecycle) return record
        return stream.select(record) ?: throw IOException("Native source did not complete")
    }

    private fun retained(
        record: CodeModeRecord,
        invocation: CodeModeNativeInvocation,
        owner: String,
    ): CodeModeHeldNativeCell {
        val id = checkNotNull(invocation.cellId)
        val terminal = registry.completed(record.key, record.conversationId).firstOrNull {
            it.nativeCellId == id && it.nativeCompleted && it.phase == CodeModePhase.COMPLETED
        }
        terminal?.let { registry.nativeCells.completed(owner, id, it.origin.outerCallId) }
        return registry.nativeCells.take(owner, id)
    }

    /** A failure before source dispatch has a typed proof; every other start failure stays no-rerun. */
    fun failed(record: CodeModeRecord, error: Exception?): TurnOutcome.Failure {
        val chain = generateSequence<Throwable>(error) { it.cause }
            .joinToString(": ") { it.message ?: it::class.simpleName.orEmpty() }
            .ifEmpty { "runtime exception" }
        config.log("[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)}: runtime failed to start: $chain")
        val detail = if (error is CodeModeStartException) {
            registry.startup.failed(record)
            "code-mode runtime failed to start; its unstarted source is retained for retry"
        } else {
            val interrupted = "code-mode runtime start was interrupted; source was not rerun"
            registry.lose(record, interrupted)
            interrupted
        }
        return TurnOutcome.Failure(
            detail,
            cause = FailureCause.INTERNAL,
            phase = FailurePhase.MID_OUTPUT,
            salvagedUsage = noRequestUsage,
        )
    }

    fun capacity(record: CodeModeRecord, error: CodeModeCapacityException, machine: CodexCodeModeMachine): TurnOutcome {
        if (record.nativeRuntime) return failed(record, CodeModeStartException(error))
        val detail = "$CAPACITY_DETAIL: ${error.message}"
        config.log(
            "[code-mode] ${record.id.take(CODE_MODE_RECORD_LOG_CHARS)} (outer ${record.origin.outerCallId}): $detail",
        )
        return machine.interrupt(record, detail)
    }

    private fun runtime(): CodeModeRuntime = try {
        run.runtime()
    } catch (error: CancellationException) {
        throw error
    } catch (error: IOException) {
        throw CodeModeStartException(error)
    } catch (_: RuntimeException) {
        throw CodeModeStartException(IllegalStateException("code-mode host could not be opened"))
    }
}

private const val CAPACITY_DETAIL: String =
    "code-mode worker capacity reached; nothing was executed. Call exec again once a running script finishes"
