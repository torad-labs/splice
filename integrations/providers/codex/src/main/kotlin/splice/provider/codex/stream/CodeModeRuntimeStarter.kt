// NEW: only a typed failed boot permits reopening a fresh source cursor from its durable beginning.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.perf.PerfKeys
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CODE_MODE_WAIT_TOOL_NAME
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodexCodeModeMachine
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.stream.native.CodeModeNativeStartup
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.NativeCodeModeLifecycle
import splice.upstream.failure.CodeModeCapacityException
import splice.upstream.failure.CodeModeStartException
import java.io.IOException
internal class CodeModeRuntimeStarter(
    private val run: CodeModeRuntimeRun,
    private val registry: CodexCodeModeRegistry,
    private val config: CodeModeBridgeConfig,
) {
    private val native = CodeModeNativeStartup(registry)

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
        if (host is NativeCodeModeLifecycle) return native.start(host, record, context, stream)
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

    suspend fun select(record: CodeModeRecord, stream: CodeModeLiveRound?): CodeModeRecord {
        if (stream == null || runtime() !is NativeCodeModeLifecycle) return record
        return stream.select(record) ?: throw IOException("Native source did not complete")
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
            cause = FailureCause.Splice.Internal(),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence(detail),
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
