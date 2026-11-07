package splice.app.observability

import splice.core.perf.observation.NoPerfObserver
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfRecorder
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock

/** Binds one boot identity and enabled observer to the recorder's elapsed and wall-clock sources. */
internal class PerfTelemetryRecorder(
    identity: PerfIdentity,
    enabled: Boolean,
    observer: PerfFanout,
    wallClock: WallClock,
) {
    val recorder = PerfRecorder(
        identity,
        if (enabled) observer else NoPerfObserver,
        ElapsedClock(MonoClock::nowMs),
        wallClock,
    )
}
