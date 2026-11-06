package splice.core.config

import splice.core.util.Cancellables
import splice.core.util.PerfWorkBudget

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
        val lease = PerfWorkBudget.acquire(
            (minOf(generation.heads.size, MAX_OBSERVED_HEADS) + 1L) * CONFIG_PUBLICATION_HEAD_BYTES,
        )
        if (lease == null) {
            mutationAt = mutationAt ?: System.currentTimeMillis()
            mutations = 2
            Cancellables.runCatchingBestEffort {
                listener.changed(
                    EffectiveConfigObservation(
                        ++sequence,
                        requireNotNull(mutationAt),
                        emptyMap(),
                        emptyMap(),
                        mutationAt,
                        false,
                    ),
                )
            }.onFailure { mutations = 2 }
            return
        }
        lease.use { publishHeld(listener, generation) }
    }

    private fun publishHeld(listener: EffectiveConfigObserver, generation: ConfigSnapshotCache.Generation) {
        val now = System.currentTimeMillis()
        val unknown = mutationAt.takeIf { mutations > 1 }
        val observation = EffectiveConfigObservation(
            ++sequence,
            if (unknown == null) mutationAt ?: now else now,
            generation.global.effective.asMap(),
            generation.heads.entries.asSequence().take(MAX_OBSERVED_HEADS)
                .associate { it.key to it.value.effective.asMap() },
            unknown,
            generation.heads.size <= MAX_OBSERVED_HEADS,
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

// why: Bound per-head configuration copies in one notification; overflow is reported as incomplete.
private const val MAX_OBSERVED_HEADS = 32

// why: Charge each copied effective configuration, including the global map, to the shared work budget.
private const val CONFIG_PUBLICATION_HEAD_BYTES = 32L * 1_024
