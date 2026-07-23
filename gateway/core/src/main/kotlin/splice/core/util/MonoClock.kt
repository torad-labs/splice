// NEW: monotonic wall-clock hybrid for idle/deadline budgets. System.currentTimeMillis jumps on
// sleep/wake and NTP steps, which made TurnWatchdog/InflightGate.Slot either abort healthy turns
// (forward jump) or pin gate slots forever (backward jump). This clock advances only with
// System.nanoTime (monotonic) while still reporting values near epoch-ms so existing log lines
// and injected test clocks stay comparable.
package splice.core.util

import java.util.concurrent.TimeUnit

public object MonoClock {
    private val originMs: Long = System.currentTimeMillis()
    private val originNs: Long = System.nanoTime()

    /** Milliseconds since an arbitrary origin, advancing only with monotonic nano time. */
    public fun nowMs(): Long = originMs + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - originNs)
}
