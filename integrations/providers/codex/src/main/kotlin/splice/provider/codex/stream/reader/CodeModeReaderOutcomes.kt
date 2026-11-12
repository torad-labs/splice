package splice.provider.codex.stream.reader

import kotlinx.coroutines.CancellationException
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.stream.CodeModeLiveRound
import splice.provider.codex.stream.reader.CodeModeReaderFailures.HEAD_STOPPED
import splice.provider.codex.stream.reader.CodeModeReaderFailures.SOURCE_DISPOSED
import splice.provider.codex.stream.source.CodeModeSourceCapture
import java.io.IOException

/** Only an unnamed reader death converges through LOST; named source and runtime faults keep their attribution. */
internal object CodeModeReaderTermination {
    suspend fun ended(source: CodeModeLiveRound?): Boolean {
        if (source?.unexpectedDeath != true) return false
        source.outcome()
        return true
    }
}

/** Owns the result of the upstream reader independently of the script's observation and client steps. */
internal class CodeModeReaderOutcomes(
    private val log: LogSink,
    private val capture: CodeModeSourceCapture,
    private val lifecycle: CodeModeRoundLifecycle,
    private val billing: CodeModeRoundBilling,
) {
    @Volatile var unexpectedDeath = false
        private set

    @Volatile var upstreamEnded = false

    @Volatile var permanentEnding: TurnOutcome.Failure? = null
        private set

    private val stoppedByHead: Boolean get() = lifecycle.stoppedByHead
    private val sourceInterrupted: Boolean get() = lifecycle.sourceInterrupted
    private val sourceLost: Boolean get() = lifecycle.executionLost || capture.disposed
    private val localFailure: TurnOutcome.Failure? get() = lifecycle.localFailure

    /** The reader ended on an unclassified throwable (Oct 2: a ConcurrentModificationException
     *  out of the record's save). Its source had no terminal, so the cell reading it waited forever and nothing
     *  was logged. The source fails, the record is lost as [failed] loses it, and the throwable's class is named. */
    fun died(cause: Throwable?) {
        val unnamed = CodeModeReaderFailures.unexpected(cause) ?: return
        upstreamEnded = true
        unexpectedDeath = !capture.itemComplete
        log(
            "[code-mode] upstream source reader died (${unnamed::class.simpleName}): " +
                CodeModeReaderFailures.SOURCE_FAILED,
        )
        capture.source.fail(CodeModeReaderFailures.SOURCE_FAILED)
        synchronized(lifecycle) {
            if (stoppedByHead) return
            Cancellables.runCatchingBestEffort {
                capture.interrupted(
                    CodeModeReaderFailures.SOURCE_FAILED,
                )
            }.onFailure { failure ->
                log("[code-mode] the dead reader's record was not saved as lost (${failure::class.simpleName})")
            }
        }
    }

    fun finish(outcome: TurnOutcome) = synchronized(lifecycle) {
        billing.reported(outcome)
        if (stoppedByHead) throw CancellationException(HEAD_STOPPED)
        localFailure?.let { return@synchronized it }
        if (capture.unavailable) {
            lifecycle.executionLost = true
            capture.source.fail(SOURCE_DISPOSED)
            return@synchronized outcome
        }
        lifecycle.sourceInterrupted = capture.unfinished && CodeModeReaderFailures.tornSource(outcome)
        // The capture loses a source its terminal does not certify, which closes the cell a client step may still be
        // advancing. Set first, so that step ends as a torn source's step does, or with a permanent failure as it is.
        lifecycle.executionLost = !sourceInterrupted && capture.rejects(outcome)
        permanentEnding = (outcome as? TurnOutcome.Failure)?.takeIf { sourceLost && it.cause.permanent }
        Cancellables.runCatchingBestEffort { capture.finish(outcome) }
            .getOrElse { return@synchronized lifecycle.reject(it) }
        if (sourceInterrupted && outcome is TurnOutcome.Failure) {
            CodeModeReaderFailures.interrupted(outcome)
        } else {
            outcome
        }
    }

    fun cancelled(error: CancellationException) {
        upstreamEnded = true
        capture.source.fail("splice code-mode source reader cancelled; source was not rerun")
        synchronized(lifecycle) {
            if (stoppedByHead) return
            capture.interrupted("splice code-mode source reader cancelled; source was not rerun", error)
        }
    }

    fun failed(error: Exception): TurnOutcome.Failure = synchronized(lifecycle) {
        upstreamEnded = true
        if (stoppedByHead) {
            capture.source.fail(CodeModeReaderFailures.SOURCE_FAILED)
            throw CancellationException(HEAD_STOPPED, error)
        }
        localFailure?.let { return@synchronized it }
        val detail = CodeModeReaderFailures.SOURCE_FAILED
        if (error is CodeModePersistenceException) {
            capture.source.fail(detail)
            if (!capture.ready.isCompleted) capture.ready.completeExceptionally(error)
            throw error
        }
        lifecycle.sourceInterrupted = capture.unfinished && error is IOException
        // Splice's own non-IO fault loses the record below and closes the cell a client step may be advancing, as an
        // uncertified source does. Set before the source fails, so that step ends as a torn source's step does.
        lifecycle.executionLost = !sourceInterrupted && capture.unfinished
        capture.source.fail(detail)
        capture.interrupted(detail)
        CodeModeReaderFailures.outcome(error, admitted = capture.record != null)
    }
}
