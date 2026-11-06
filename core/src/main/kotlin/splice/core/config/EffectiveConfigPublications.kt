package splice.core.config

import splice.core.util.Cancellables

/** Serializes bounded immutable notifications; persistence, hashing and gate reads belong to the app worker. */
internal class EffectiveConfigPublications(private val snapshots: ConfigSnapshotCache) {
    @Volatile
    var observer: EffectiveConfigObserver? = null
    private var sequence = 0L
    private var mutationAt: Long? = null
    private var mutations = 0
    private var published: ConfigSnapshotCache.Generation? = null

    @Synchronized
    fun mutated() {
        if (observer != null) {
            if (mutationAt == null) mutationAt = System.currentTimeMillis()
            mutations++
        }
    }

    @Synchronized
    fun publish(generation: ConfigSnapshotCache.Generation) {
        val listener = observer ?: return
        if (snapshots.current() !== generation || published === generation) return
        val now = System.currentTimeMillis()
        val unknown = mutationAt.takeIf { mutations > 1 }
        val observation = EffectiveConfigObservation(
            ++sequence,
            if (unknown == null) mutationAt ?: now else now,
            generation.global.effective.asMap(),
            generation.heads.mapValues { it.value.effective.asMap() },
            unknown,
        )
        published = generation
        mutationAt = null
        mutations = 0
        Cancellables.runCatchingBestEffort { listener.changed(observation) }.onFailure {
            mutationAt = observation.unknownFromMs ?: observation.atMs
            mutations = 2
        }
    }
}
