// NEW: only a typed failed boot permits reopening a fresh source cursor from its durable beginning.
package splice.provider.codex.stream

import kotlinx.coroutines.CancellationException
import splice.core.perf.PerfKeys
import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.provider.codex.CODE_MODE_RECORD_LOG_CHARS
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRunContext
import splice.provider.codex.CodeModeRuntimeRun
import splice.provider.codex.CodexCodeModeRegistry
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
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
            if (stream == null) {
                starting(record, context, null)
            } else {
                stream.source.whileStarting { starting(record, context, stream) }
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
                // Capacity refused before source dispatch. Reclaim one parked engine, then recheck capacity.
                if (registry.evictIdleCell() == null) {
                    registry.retainedCells.logRefusal()
                    throw error
                }
            }
        }
    }

    private suspend fun open(record: CodeModeRecord, context: CodeModeRunContext, stream: CodeModeLiveRound?) =
        if (stream == null) {
            runtime().startSession(record.key, record.origin.source, context.turn.tools, context.turn.descriptions)
        } else {
            runtime().startStreamingSession(
                record.key,
                stream.source.view(),
                context.turn.tools,
                context.turn.descriptions,
            )
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
