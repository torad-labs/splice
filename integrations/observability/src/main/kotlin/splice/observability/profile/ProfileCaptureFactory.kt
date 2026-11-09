package splice.observability.profile

import splice.core.perf.runtime.PerfProfileRequest
import java.nio.file.Path
import java.util.concurrent.ScheduledExecutorService

public fun interface ProfileCaptureFactory {
    public fun create(
        request: PerfProfileRequest,
        nativeExecutable: Path?,
        supervisor: ScheduledExecutorService,
    ): ProfileCapture
}

internal object DefaultProfileCaptures : ProfileCaptureFactory {
    override fun create(
        request: PerfProfileRequest,
        nativeExecutable: Path?,
        supervisor: ScheduledExecutorService,
    ): ProfileCapture =
        if (request.profiler == "jfr") {
            JfrCapture(
                request,
            )
        } else {
            NativeCapture(request, checkNotNull(nativeExecutable), supervisor)
        }
}
