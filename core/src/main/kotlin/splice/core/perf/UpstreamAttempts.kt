// NEW: the upstream attempt counter and the timing pair each attempt publishes, split out of TurnPerf so that class
// stays under the function-count wall. One attempt is current at a time: a retry begins a new one and discards what its
// predecessor measured, and a late observation from a past attempt is dropped rather than published as the current one.
package splice.core.perf

/** The upstream attempts of one turn, sharing its owner's lock and counters so each publication lands in the same
 *  atomic snapshot as everything else the turn measured. */
public class UpstreamAttempts internal constructor(
    private val lock: TurnPerfLock,
    private val counters: MutableMap<String, Long>,
) {
    private var current = 0L

    /** Whether [attempt] is the one in flight. */
    internal fun isCurrent(attempt: Long): Boolean = attempt == current

    /** Retain each attempt start while atomically discarding its predecessor's timing measurements. */
    public fun begin(): Long = synchronized(lock) {
        for (milestones in UpstreamMilestones.entries) {
            counters.remove(milestones.arrival)
            counters.remove(milestones.wait)
        }
        ++current
        counters[PerfKeys.TRANSPORT_ATTEMPT_STARTS] = current
        current
    }

    /** Publish only the current attempt's observed pair. Legacy SSE parameter names and JVM overload remain. */
    @JvmOverloads
    public fun record(
        attempt: Long,
        writtenAt: Long?,
        firstByteAt: Long?,
        milestones: UpstreamMilestones = UpstreamMilestones.SSE_WRITE,
    ) {
        synchronized(lock) {
            if (attempt != current || writtenAt == null) return
            counters[milestones.arrival] = writtenAt
            counters.remove(milestones.wait)
            if (firstByteAt != null && firstByteAt >= writtenAt) {
                counters[milestones.wait] = firstByteAt - writtenAt
            }
        }
    }
}
