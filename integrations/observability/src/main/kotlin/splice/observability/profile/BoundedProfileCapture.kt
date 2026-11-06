package splice.observability.profile

import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A stuck JVM operation quarantines the one worker; deadlines never admit another capture over it. */
internal class BoundedProfileCapture(
    private val capture: ProfileCapture,
    private val run: ProfileRun,
    private val supervisor: ScheduledExecutorService,
) : ProfileCapture {
    override fun start() = bounded { capture.start() }
    override fun finish(destination: Path) = bounded { capture.finish(destination) }
    override fun close() = bounded { capture.close() }

    private fun bounded(action: ProfileCapturePhase) {
        val deadline = supervisor.schedule({ run.abort() }, ProfileLimits.CONTROL_SECONDS, TimeUnit.SECONDS)
        try {
            action.perform()
        } finally {
            deadline.cancel(false)
        }
    }
}

private fun interface ProfileCapturePhase {
    fun perform()
}
