package splice.core.perf.observation

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock

/** No mutable process-global recorder. Production injects a recorder owned by its head. */
public object PerfRecorders {
    public fun disabled(): PerfRecorder = PerfRecorder(
        PerfIdentity("disabled", "unknown", "unknown", "disabled"),
        NoPerfObserver,
        ElapsedClock(MonoClock::nowMs),
        WallClock(System::currentTimeMillis),
    )
}
