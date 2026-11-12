package splice.provider.codex.stream

import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.failure.ProtocolFault
import splice.core.failure.Tear
import splice.core.turn.TurnOutcome
import splice.core.turn.noRequestUsage
import splice.core.util.SafeFailureText
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.state.CodeModeWorkerRecovery
import splice.upstream.failure.CodeModeInfrastructureException
import splice.upstream.failure.CodeModeTimeoutException
import splice.upstream.failure.CodeModeWorkerLostException
import java.io.IOException

/** Classifies runtime loss without exposing script bytes or rerunning accepted work. */
internal class CodeModeRuntimeFailures(private val registry: CodexCodeModeRegistry) {
    private val workerRecovery = CodeModeWorkerRecovery(registry)

    fun poison(record: CodeModeRecord, message: String): TurnOutcome.Failure {
        record.cellStartedAt = null
        registry.lose(record, message)
        return TurnOutcome.Failure(
            cause = FailureCause.Splice.Protocol(ProtocolFault.CODE_MODE_STATE),
            at = Position(Moment.MID_CONTENT, streamBegan = true),
            evidence = FailureEvidence(message),
            salvagedUsage = noRequestUsage,
        )
    }

    fun lostMessage(record: CodeModeRecord): String =
        "completed client call ids=${record.results.keys}; source was not rerun"

    fun ioFailure(record: CodeModeRecord, error: IOException): TurnOutcome.Failure = when (error) {
        is CodeModeTimeoutException -> poison(record, "code-mode runtime timed out; ${lostMessage(record)}")
        is CodeModeInfrastructureException -> poison(
            record,
            "code-mode infrastructure failure ${error.category}/${error.faultClass}; ${lostMessage(record)}",
        )
        is CodeModeSourceInterruptedException -> {
            record.cellStartedAt = null
            val message = "upstream code-mode source interrupted; ${lostMessage(record)}"
            registry.lose(record, message)
            error.verdict ?: TurnOutcome.Failure(
                cause = FailureCause.Path.Torn(Tear.RESET, toolOpen = false),
                at = Position(Moment.MID_CONTENT, streamBegan = true),
                evidence = FailureEvidence(message),
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
