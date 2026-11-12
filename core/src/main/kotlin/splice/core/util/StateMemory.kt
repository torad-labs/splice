// NEW: V4-254 — remembers the last state a repeating check reported, so a condition that stays true is said once.
package splice.core.util

import java.util.concurrent.atomic.AtomicReference

/**
 * The last failing state one repeating check reported. A status poll reads the same missing credential every few
 * seconds; the journal needs the change, not the repetition: [isNews] is true the first time a state is seen and again
 * after [clear] (the check passed), and false while the same state repeats.
 */
public class StateMemory {
    private val last = AtomicReference<String?>(null)

    public fun isNews(state: String): Boolean = last.getAndSet(state) != state

    public fun clear() {
        last.set(null)
    }
}
