// PORT-OF: UsageHud.kt (UsageStore) @ d8653a0 — invariants unchanged: the 5h ring's CME-safe
// in-memory mutation, moved verbatim onto its own collaborator (HD-24, 2026-08-17). CALLERS HOLD
// ringLock for every access; the deque itself never escapes the lock (the audit's CME finding).
package splice.gateway.usage

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// New writes aggregate by minute (~300 rows/5h); retain the legacy high cap so existing
// per-turn files load without data loss and are compacted naturally as the window advances.
private const val MAX_RING_ENTRIES = 50_000
private const val USAGE_BUCKET_MS = 60_000L

/** The live 5h output-token ring: CME-safe in-memory mutation under [ringLock], loaded from
 *  [ringFile] on first use (or after a process restart). */
internal class UsageRing(private val ringFile: UsageRingFile, initialCutoff: Long) {
    private val usageJson = UsageJson()
    private val ringLock = Any()
    private val cachedRing = ArrayDeque<JsonObject>()
    private var ringLoaded = false
    private var mutationVersion = 0L

    init {
        // Load/trim the bounded legacy ring while the head is assembled, not on the first
        // completed turn. Every append on the turn path is then memory-only plus an async enqueue.
        synchronized(ringLock) { loadRingUnderLock(initialCutoff) }
    }

    // In-memory updates are immediate. Returns whether an entry was appended, so the caller
    // schedules a coalesced flush only then — equivalent to the pre-split `if (outputTokens <= 0)
    // return`, which skipped scheduling by skipping this whole block.
    internal fun appendOutputTokens(now: Long, outputTokens: Long): Boolean {
        if (outputTokens <= 0) return false
        synchronized(ringLock) {
            val ring = loadRingUnderLock(now - FIVE_HOURS_MS)
            val previous = ring.lastOrNull()
            val previousTs = previous?.let { usageJson.num(it["timestamp"]) }
            val sameBucket = previousTs?.let { it / USAGE_BUCKET_MS == now / USAGE_BUCKET_MS } == true
            if (previous != null && sameBucket) {
                ring.removeLast()
                ring.addLast(usageEntry(now, (usageJson.num(previous[OUTPUT_TOKENS]) ?: 0) + outputTokens))
            } else {
                ring.addLast(usageEntry(now, outputTokens))
            }
            while (ring.size > MAX_RING_ENTRIES) ring.removeFirst()
            mutationVersion += 1
        }
        return true
    }

    /** Entry count + summed output tokens for entries newer than [cutoff] (readState). */
    internal fun stats(cutoff: Long): Pair<Int, Long> = synchronized(ringLock) {
        val ring = loadRingUnderLock(cutoff)
        ring.size to ring.sumOf { usageJson.num(it[OUTPUT_TOKENS]) ?: 0 }
    }

    /** Trimmed-to-cutoff snapshot + the version it was taken at (flushNow). */
    internal fun trimmedSnapshot(cutoff: Long): Pair<List<JsonObject>, Long> = synchronized(ringLock) {
        val ring = loadRingUnderLock(cutoff)
        ring.toList() to mutationVersion
    }

    /** Untrimmed snapshot of the cached ring + the version it was taken at (flushScheduled). */
    internal fun snapshot(): Pair<List<JsonObject>, Long> = synchronized(ringLock) {
        cachedRing.toList() to mutationVersion
    }

    /** Whether a mutation has landed since [version] was persisted (flushScheduled's reschedule check). */
    internal fun isDirtierThan(version: Long): Boolean = synchronized(ringLock) { mutationVersion > version }

    /**
     * Return the live 5h ring, loading from disk only on first use (or after a process restart).
     * Entries older than [cutoff] are dropped. CALLERS HOLD [ringLock] — the deque itself must
     * never escape the lock (mutation + iteration outside it was the audit's CME finding).
     */
    private fun loadRingUnderLock(cutoff: Long): ArrayDeque<JsonObject> {
        if (!ringLoaded) {
            cachedRing.clear()
            cachedRing.addAll(ringFile.readEntriesFromDisk())
            ringLoaded = true
        }
        while (cachedRing.isNotEmpty() && (usageJson.num(cachedRing.first()["timestamp"]) ?: 0) <= cutoff) {
            cachedRing.removeFirst()
        }
        return cachedRing
    }

    private fun usageEntry(timestamp: Long, outputTokens: Long): JsonObject = buildJsonObject {
        put("timestamp", timestamp)
        put(OUTPUT_TOKENS, outputTokens)
    }
}
