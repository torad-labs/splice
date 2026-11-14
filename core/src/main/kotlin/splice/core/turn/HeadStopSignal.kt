// NEW: a head generation fences source cancellation before its turn jobs unwind.
package splice.core.turn

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One head generation's monotonic stop fence. A restarted head creates a fresh signal, never resets this one.
 *  #525 (2026-11-12): it has two states. Retired means no new turn starts, while the turns already answering run
 *  on beside the next daemon; stopping means everything stops. Code mode reads [isStopping] alone, so a round a
 *  retiring head keeps is not cut by the retire. */
public class HeadStopSignal : AbstractCoroutineContextElement(HeadStopKey) {
    @Volatile private var stopping = false

    @Volatile private var retired = false

    public val isStopping: Boolean get() = stopping

    /** True once the head retired or stopped: no new turn of this generation starts. */
    public val refusesNewTurns: Boolean get() = retired || stopping

    /** Publish head ownership before cancelling any turn or provider-owned source. */
    public fun stop() {
        stopping = true
    }

    /** #525: the head retires. No new turn starts; the turns it keeps are not told to stop. */
    public fun retire() {
        retired = true
    }
}

/** Carries the generation's stop ownership through the head's turn and child coroutine contexts. */
public object HeadStopKey : CoroutineContext.Key<HeadStopSignal>
