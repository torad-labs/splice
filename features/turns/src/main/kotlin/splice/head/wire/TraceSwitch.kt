// NEW: V4-444 — the head's trace is switched on and off WITHOUT a restart.
//
// The trace used to exist only for a head that booted with it on, so flipping the capture switch changed splice.toml and
// nothing else until the next start. The store now always exists and this flag decides, per request, whether the next
// turn is recorded. The turn path reads it once as a request arrives, so a flip applies to the very next request and
// never reaches a turn already in flight: that turn finishes the way it began.
package splice.head.wire

import splice.core.util.WallClock
import splice.head.trace.body.TraceBodySaves
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Whether a head records a trace of the NEXT request. Seeded from the head's config at boot, then moved by the capture
 *  switch; the file the operator keeps is still splice.toml. It also holds when saving stopped while the switch stayed on
 *  (the disk at the free-space floor, or the body budget spent), so Settings can say so instead of a size that looks
 *  live (walk on d0ec523b5: "25.2 GB counted" while nothing had been saved since 10:44 AM). With [kept], that time is
 *  read at the start and kept as it changes, so a restart while saving is stopped still shows when it first did. */
public class TraceSwitch(
    initial: Boolean,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    private val kept: TraceStopFile? = null,
) : TraceBodySaves {
    private val flag = AtomicBoolean(initial)
    private val stop = AtomicReference<TraceStop?>(kept?.load())

    /** When saving first stopped since the last request saved whole, and why; null while requests save whole. */
    public val stopped: TraceStop? get() = stop.get()

    override fun refused(reason: String) {
        if (stop.compareAndSet(null, TraceStop(clock(), reason))) keep()
    }

    override fun saved() {
        if (stop.getAndSet(null) != null) keep()
    }

    /** Writes the stop as it stands at this moment, so of two changes in a row the later one is the one kept. */
    @Synchronized
    private fun keep() {
        kept?.save(stop.get())
    }

    /** True when the next request is recorded. */
    public val on: Boolean get() = flag.get()

    /** Records or stops recording from the next request on. */
    public fun set(enabled: Boolean) {
        flag.set(enabled)
    }
}

/** A trace that stopped saving: the first refusal's time in epoch milliseconds, and the store's own reason. */
public data class TraceStop(val atEpochMs: Long, val reason: String)
