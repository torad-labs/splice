// NEW: a head generation fences source cancellation before its turn jobs unwind.
package splice.core.turn

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One head generation's monotonic stop fence. A restarted head creates a fresh signal, never resets this one. */
public class HeadStopSignal : AbstractCoroutineContextElement(HeadStopKey) {
    @Volatile private var stopping = false

    public val isStopping: Boolean get() = stopping

    /** Publish head ownership before cancelling any turn or provider-owned source. */
    public fun stop() {
        stopping = true
    }
}

/** Carries the generation's stop ownership through the head's turn and child coroutine contexts. */
public object HeadStopKey : CoroutineContext.Key<HeadStopSignal>
