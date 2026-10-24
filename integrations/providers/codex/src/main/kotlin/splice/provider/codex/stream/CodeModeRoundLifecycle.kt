package splice.provider.codex.stream

import splice.core.turn.HeadStopSignal
import splice.core.turn.TurnOutcome
import splice.provider.codex.stream.source.CodeModeDeliveredText
import splice.provider.codex.stream.source.CodeModeSourceCapture

/** Source admission, client delivery and billing share this monitor and the same live ownership state. */
internal class CodeModeRoundLifecycle(
    private val capture: CodeModeSourceCapture,
    private val headStop: HeadStopSignal?,
) {
    @Volatile var headStopped: Boolean = false

    @Volatile var executionLost: Boolean = false

    @Volatile var localFailure: TurnOutcome.Failure? = null

    val stoppedByHead: Boolean get() = headStopped || headStop?.isStopping == true

    /** A cutoff may update a completed cell's history, while a removed or stopped owner cannot write it back. */
    fun delivered(witness: CodeModeDeliveredText) = synchronized(this) {
        if (stoppedByHead || executionLost) return@synchronized
        if (capture.disposed || localFailure != null) return@synchronized
        capture.delivered(witness)
    }
}
