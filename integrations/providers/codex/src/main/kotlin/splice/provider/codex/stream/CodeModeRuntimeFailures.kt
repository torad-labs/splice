package splice.provider.codex.stream

import splice.core.turn.FailureCause
import splice.core.turn.FailurePhase
import splice.core.turn.FailureTraits
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeWorkerRecovery
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException

/** Classifies runtime loss without exposing script bytes or rerunning accepted work. */
internal class CodeModeRuntimeFailures(private val registry: CodexCodeModeRegistry) {
    private val workerRecovery = CodeModeWorkerRecovery(registry)

    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure {
        record.cellStartedAt = null
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

    fun ioFailure(record: CodeModeRecord, error: IOException): TurnOutcome.Failure = when (error) {
        is CodeModeSourceInterruptedException -> {
            record.cellStartedAt = null
            val message = "upstream code-mode source interrupted; ${lostMessage(record)}"
            registry.lose(record, message)
            error.verdict ?: TurnOutcome.Failure(
                message,
                cause = FailureCause.UPSTREAM_CONN_RESET,
                phase = FailurePhase.MID_OUTPUT,
            )
        }
        is CodeModeWorkerLostException -> {
            record.cellStartedAt = null
            workerRecovery.lost(record)
        }
        else -> poison(record, runtimeFailure(error, record))
    }

    /** Throwable messages may contain script or tool bytes. Only the known capacity message is public. */
    fun runtimeFailure(error: Throwable, record: CodeModeRecord): String {
        val firstLine = error.message?.lineSequence()?.firstOrNull()?.trim()
        val detail = if (firstLine == "worker pool exhausted") firstLine else "message withheld"
        return "code-mode runtime failed: ${error::class.simpleName}: $detail${SafeFailureText.site(error)}; " +
            "accepted results=${record.results.size}; source was not rerun"
    }
}
