package splice.sessions.registry

import splice.core.memory.HeapCategory
import splice.core.memory.HeapObservation
import splice.core.memory.HeapOwnerState
import splice.core.memory.HeapOwners
import splice.core.memory.HeapRelease
import splice.core.memory.HeapReservations

private const val MAX_SEEN_CACHE_ENTRIES = 2_048
private const val MAX_SEEN_CACHE_BYTES = 4L shl 20
private const val SEEN_CACHE_INITIAL_CAPACITY = 16
private const val SEEN_CACHE_LOAD_FACTOR = 0.75f
private const val SEEN_METADATA_BYTES = 640L
private const val SEEN_PROJECTION_BYTES = 448L

/** Bounded metadata ownership only: eviction rereads the record, never removes it from a listing. */
internal class SeenSessionCache(private val heap: HeapReservations? = null) {
    private class Held(val seen: SeenSession, val bytes: Long, val observation: HeapObservation?) {
        var ended: SessionRecord? = null
        var endedObservation: HeapObservation? = null

        fun retire(reason: HeapRelease) {
            observation?.close(reason)
            endedObservation?.close(reason)
        }
    }

    private val held = LinkedHashMap<String, Held>(SEEN_CACHE_INITIAL_CAPACITY, SEEN_CACHE_LOAD_FACTOR, true)
    private var bytes = 0L

    @Synchronized
    operator fun get(id: String): SeenSession? = held[id]?.seen

    @Synchronized
    operator fun set(id: String, seen: SeenSession) {
        if (held[id]?.seen == seen) return
        val size = retainedBytes(seen)
        // The existing bound budgets room for the lazy projection. Observe it only once it exists.
        val escaped = heap?.let {
            HeapOwners.observe(
                seen,
                it,
                size - SEEN_PROJECTION_BYTES,
                HeapCategory.TRANSCRIPT_CACHE,
                HeapOwnerState.AWAITING_COLLECTION,
            )
        }
        held.remove(id)?.let {
            bytes -= it.bytes
            it.retire(HeapRelease.CLOSED)
        }
        if (size > MAX_SEEN_CACHE_BYTES) return
        while (held.isNotEmpty() && overBudget(size)) {
            val oldest = held.entries.iterator().next()
            bytes -= oldest.value.bytes
            oldest.value.retire(HeapRelease.EVICTED)
            held.remove(oldest.key)
        }
        val item = Held(seen, size, escaped?.share())
        item.observation?.let { HeapOwners.keep(item, it) }
        held[id] = item
        bytes += size
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        val entries = held.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.key !in ids) {
                bytes -= entry.value.bytes
                entry.value.retire(HeapRelease.EVICTED)
                entries.remove()
            }
        }
    }

    @Synchronized
    fun ended(seen: SeenSession): SessionRecord {
        val known = held[seen.sessionId]?.takeIf { it.seen == seen }
        return known?.ended ?: project(seen).also { record ->
            val escaped = heap?.let {
                val size = SEEN_PROJECTION_BYTES + retainedBytes(seen) - SEEN_METADATA_BYTES
                HeapOwners.observe(record, it, size, HeapCategory.TRANSCRIPT_CACHE, HeapOwnerState.AWAITING_COLLECTION)
            }
            if (known != null) {
                known.ended = record
                known.endedObservation = escaped?.share()
                known.endedObservation?.let { HeapOwners.keep(known, it) }
            }
        }
    }

    private fun overBudget(size: Long): Boolean =
        held.size >= MAX_SEEN_CACHE_ENTRIES || bytes + size > MAX_SEEN_CACHE_BYTES

    // Includes space for the lazy projection, whose strings are the same recorded metadata.
    private fun retainedBytes(seen: SeenSession): Long {
        val characters = seen.sessionId.length + (seen.name?.length ?: 0) +
            (seen.cwd?.length ?: 0) + (seen.head?.length ?: 0)
        return SEEN_METADATA_BYTES + 2L * characters
    }

    private fun project(kept: SeenSession): SessionRecord = SessionRecord(
        sessionId = kept.sessionId,
        name = kept.name,
        status = SessionStatus(),
        route = kept.head?.let { SessionRoute.Head(it) } ?: SessionRoute.Unknown,
        availability = SessionAvailability.GONE,
        process = SessionProcess(
            pid = null,
            cwd = kept.cwd,
            startedAt = null,
            updatedAt = kept.lastActivityMs,
            messagingSocketPath = null,
        ),
        client = SessionClient(kind = null, version = null),
    )
}
