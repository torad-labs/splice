package splice.upstream.retry

import splice.core.util.ElapsedClock
import splice.core.util.MonoClock

/** The original constructor remains source compatible, including its trailing clock lambda. */
public object ObservedInflightGate {
    public fun create(
        maxInflight: LiveLimit,
        maxQueued: LiveLimit,
        observation: GateObservation,
        clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
    ): InflightGate = InflightGate(maxInflight, maxQueued, clock).also {
        it.observations.install(observation)
        it.sampleObservations()
    }
}
