package splice.observability.profile

import jdk.jfr.FlightRecorder
import jdk.jfr.Recording
import jdk.jfr.RecordingState
import splice.core.perf.runtime.PerfProfileRequest
import java.nio.file.Path
import java.time.Duration

/** Empty settings plus an event allowlist excludes content-bearing JFR events and forced GC. */
internal class JfrCapture(private val request: PerfProfileRequest) : ProfileCapture {
    private var recording: Recording? = null
    override val fromMs: Long? get() = recording?.startTime?.toEpochMilli()
    override val untilMs: Long? get() = recording?.stopTime?.toEpochMilli()

    override fun start() {
        if (!FlightRecorder.isAvailable() || request.mode == "wall") throw ProfileUnavailable()
        val capture = Recording()
        recording = capture
        capture.name = "splice-performance"
        capture.maxSize = ProfileLimits.ARTIFACT_BYTES
        capture.maxAge = Duration.ofSeconds(request.durationSeconds.toLong())
        capture.duration = Duration.ofSeconds(request.durationSeconds.toLong())
        capture.setToDisk(true)
        when (request.mode) {
            "cpu" -> capture.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(SAMPLE_PERIOD_MS))
            "alloc" -> capture.enable("jdk.ObjectAllocationSample").withStackTrace()
            "lock" -> capture.enable(
                "jdk.JavaMonitorEnter",
            ).withThreshold(Duration.ofMillis(LOCK_THRESHOLD_MS)).withStackTrace()
            else -> throw ProfileUnavailable()
        }
        capture.start()
    }

    override fun finish(destination: Path) {
        val capture = checkNotNull(recording)
        if (capture.state == RecordingState.RUNNING) capture.stop()
        val stream = capture.getStream(null, null) ?: throw java.io.IOException("JFR produced no stream")
        stream.use { ProfileCopy.copy(it, destination) }
    }

    override fun close() {
        recording?.close()
        recording = null
    }
}

// Sample execution stacks at 20 ms intervals to bound the frequency of optional CPU profiling events.
private const val SAMPLE_PERIOD_MS = 20L

// Record monitor contention lasting at least 10 ms, excluding brief acquisitions from the bounded capture.
private const val LOCK_THRESHOLD_MS = 10L
