// NEW: conversation-lifetime summary-dedup state (2026-08-26). The sequential_cutoff recap
// staircase spans CLIENT turns, not just rounds: every Claude Code tool round-trip is a separate
// POST, and the backend opens the next response by restating the prior logical summary chain.
// Losing an entry (restart, LRU, byte pressure, idle TTL) degrades to duplicate display, never an
// error. At rest, 64 entries retain at most 64 MiB of text plus records/maps. A live entry can also
// hold up to 1 MiB of current-round text; leases may temporarily carry the map above 64 only when
// more than 64 distinct conversations are simultaneously waiting/running, and release restores the
// count bound. Genuinely idle entries expire after 30 minutes.
package splice.dialect.responses

import splice.core.turn.SharedSummaryParts
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock

private const val MAX_CONVERSATIONS = 64
private const val MAX_PARTS_PER_CONVERSATION = 512
private const val MAX_BYTES_PER_CONVERSATION = 1024L * 1024
private const val IDLE_TTL_MS = 30L * 60 * 1000

/** One [SharedSummaryParts] per complete session+conversation identity, LRU/idle-bounded. Missing
 *  either identity returns null, so the caller uses TurnMeta.summaryParts: cross-turn dedup is less
 *  important than preventing two same-opener clients from suppressing each other's text. A resume
 *  under a NEW session id deliberately starts empty for the same reason; its one-time cost is a
 *  recap displaying again. */
internal class ConversationSummaryParts(
    private val maxConversations: Int = MAX_CONVERSATIONS,
    private val maxPartsPerConversation: Int = MAX_PARTS_PER_CONVERSATION,
    private val maxBytesPerConversation: Long = MAX_BYTES_PER_CONVERSATION,
    private val ttlMs: Long = IDLE_TTL_MS,
    private val clock: ElapsedClock = ElapsedClock(MonoClock::nowMs),
) {
    private data class Entry(val scope: SummaryRoundScope, var at: Long, var leases: Int = 0)

    // Iteration order = least-recently-used first. A leased entry is never evicted: doing so would
    // create a second mutex/state for the same key while the first round is waiting or running.
    private val convos = LinkedHashMap<String, Entry>()
    private val lock = Any()

    /** Uses the SAME injective session+conversation encoding as ResponsesWsIdentity. */
    fun ownerForConversation(sessionId: String?, conversationKey: String?): SummaryRoundOwner? =
        ResponsesConversationIdentity.chainKey(sessionId, conversationKey)?.let(::ConversationRoundOwner)

    /** State-only compatibility view for tests; production acquires [ownerForConversation] at drive. */
    fun forConversation(sessionId: String?, conversationKey: String?): SharedSummaryParts? {
        val key = ResponsesConversationIdentity.chainKey(sessionId, conversationKey) ?: return null
        return synchronized(lock) {
            val entry = touchOrCreateLocked(key, clock())
            trimLocked()
            entry.scope.parts
        }
    }

    private inner class ConversationRoundOwner(private val key: String) : SummaryRoundOwner {
        override suspend fun <T> withRound(task: SummaryRoundTask<T>): T {
            val entry = acquire(key)
            return try {
                entry.scope.withRound(task)
            } finally {
                release(key, entry)
            }
        }
    }

    private fun acquire(key: String): Entry = synchronized(lock) {
        val entry = touchOrCreateLocked(key, clock())
        entry.leases++
        trimLocked()
        entry
    }

    private fun release(key: String, entry: Entry) {
        synchronized(lock) {
            entry.leases--
            if (convos[key] === entry) {
                convos.remove(key)
                entry.at = clock()
                convos[key] = entry
            }
            sweepLocked(clock())
            trimLocked()
        }
    }

    private fun touchOrCreateLocked(key: String, now: Long): Entry {
        sweepLocked(now)
        val entry = convos.remove(key) ?: Entry(
            SummaryRoundScope(
                // Deliberate second construction site: this instance is conversation-lifetime.
                SharedSummaryParts(maxPartsPerConversation, maxBytesPerConversation),
            ),
            now,
        )
        entry.at = now
        convos[key] = entry
        return entry
    }

    private fun sweepLocked(now: Long) {
        val cutoff = now - ttlMs
        convos.entries.filter { it.value.leases == 0 && it.value.at < cutoff }.map { it.key }
            .forEach(convos::remove)
    }

    private fun trimLocked() {
        while (convos.size > maxConversations) {
            val victim = convos.entries.firstOrNull { it.value.leases == 0 } ?: return
            convos.remove(victim.key)
        }
    }
}
