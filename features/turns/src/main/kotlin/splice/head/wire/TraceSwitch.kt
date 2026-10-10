// NEW: V4-444 — the head's trace is switched on and off WITHOUT a restart.
//
// The trace used to exist only for a head that booted with it on, so flipping the capture switch changed splice.toml and
// nothing else until the next start. The store now always exists and this flag decides, per request, whether the next
// turn is recorded. The turn path reads it once as a request arrives, so a flip applies to the very next request and
// never reaches a turn already in flight: that turn finishes the way it began.
package splice.head.wire

import java.util.concurrent.atomic.AtomicBoolean

/** Whether a head records a trace of the NEXT request. Seeded from the head's config at boot, then moved by the capture
 *  switch; it holds nothing else, so the file the operator keeps is still splice.toml. */
public class TraceSwitch(initial: Boolean) {
    private val flag = AtomicBoolean(initial)

    /** True when the next request is recorded. */
    public val on: Boolean get() = flag.get()

    /** Records or stops recording from the next request on. */
    public fun set(enabled: Boolean) {
        flag.set(enabled)
    }
}
