package splice.head.observe

import splice.core.turn.TurnObservation

/** Admission-time offer port. Return null if no turn events are wanted; never wait, run a mod, or do I/O here. */
public interface TurnObservers {
    public fun begin(facts: ObservedTurnFacts): TurnObservation?
}

public object NoTurnObservers : TurnObservers {
    override fun begin(facts: ObservedTurnFacts): TurnObservation? = null
}

/** Local identity and routing facts only. Launch ownership is a UUID, never a proof or credential. */
public data class ObservedTurnFacts(
    val sessionId: String?,
    val launchOwner: String?,
    val head: String,
    val model: String,
    val compact: Boolean,
    val agent: String?,
    val atEpochMs: Long,
)
