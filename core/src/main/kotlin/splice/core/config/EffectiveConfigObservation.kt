package splice.core.config

/** Immutable effective values observed at publication. The listener must only enqueue bounded memory work. */
public data class EffectiveConfigObservation(
    val sequence: Long,
    val atMs: Long,
    val global: Map<String, Any?>,
    val heads: Map<String, Map<String, Any?>>,
    /** A collapsed or failed publication cannot certify the intervening interval. */
    val unknownFromMs: Long? = null,
    val headsComplete: Boolean = true,
)

public fun interface EffectiveConfigObserver {
    public fun changed(observation: EffectiveConfigObservation)
}
