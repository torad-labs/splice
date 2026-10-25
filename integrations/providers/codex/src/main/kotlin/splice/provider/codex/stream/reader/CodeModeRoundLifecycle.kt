package splice.provider.codex.stream.reader

import splice.core.turn.HeadStopSignal
import splice.core.turn.TurnOutcome
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.provider.codex.CodexCodeModeRegistry
import splice.provider.codex.stream.CodeModeRejection
import splice.provider.codex.stream.source.CodeModeDeliveredText
import splice.provider.codex.stream.source.CodeModeSourceCapture

/** Source admission, client delivery and billing share this monitor and the same live ownership state. */
internal class CodeModeRoundLifecycle(
    private val capture: CodeModeSourceCapture,
    private val headStop: HeadStopSignal?,
    private val registry: CodexCodeModeRegistry,
    private val log: LogSink,
) {
    @Volatile var headStopped: Boolean = false

    @Volatile var executionLost: Boolean = false

    @Volatile var localFailure: TurnOutcome.Failure? = null

    @Volatile var sourceInterrupted: Boolean = false

    val stoppedByHead: Boolean get() = headStopped || headStop?.isStopping == true

    /** A cutoff may update a completed cell's history, while a removed or stopped owner cannot write it back. */
    fun delivered(witness: CodeModeDeliveredText) = synchronized(this) {
        if (stoppedByHead || executionLost) return@synchronized
        if (capture.disposed || localFailure != null) return@synchronized
        capture.delivered(witness)
    }

    /** Rejection revokes this source's ownership without turning an already sealed item into another execution. */
    fun reject(error: Throwable): TurnOutcome.Failure = synchronized(this) {
        val rejected = CodeModeRejection.outcome(error)
        localFailure = rejected
        sourceInterrupted = false
        capture.source.fail(rejected.message)
        try {
            if (capture.itemComplete) {
                Cancellables.runCatchingBestEffort { capture.interrupted() }.onFailure { failure ->
                    log("[code-mode] rejected response accounting was not saved (${failure::class.simpleName})")
                }
            } else {
                capture.record?.let { record ->
                    // The dispatch ticket of a source that never attached cannot outlive its round.
                    val release = Cancellables.runCatchingBestEffort { registry.startup.release(record) }
                    if (release.isFailure) {
                        val cause = release.exceptionOrNull()
                        log(
                            "[code-mode] the rejected startup admission was not released " +
                                "(${cause?.let { it::class.simpleName }})",
                        )
                    }
                }
                localFailure = CodeModeRejection.lose(capture.record, rejected, registry)
            }
        } finally {
            capture.ready.complete(null)
        }
        checkNotNull(localFailure)
    }
}
