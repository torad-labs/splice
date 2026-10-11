// NEW: V4-334 — the reasoning cache's disk writes, applied BEHIND its state lock and in the order the cache
// decided them. The cache decides under its monitor (admit a round, evict a conversation, end one at its
// compaction) and only queues the write here; the thread that made the change then drains the queue under
// `ioLock`, a monitor that guards nothing but the writes it serializes (the dated exemption in
// kt-no-blocking-io-under-monitor). A request building its input therefore never waits on the disk, and
// because every write is queued under the cache's lock, the queue's order is the order of the changes: a
// conversation evicted and begun again is deleted before its new round is appended, whichever thread drains.
package splice.dialect.responses.reasoning

import java.util.concurrent.ConcurrentLinkedQueue

internal class ReasoningCacheWrites(private val files: ReasoningCacheFiles?) {
    private val pending = ConcurrentLinkedQueue<ReasoningCacheWrite>()
    private val ioLock = Any()

    // Conversations whose append failed, guarded by ioLock: their file is gone, and later rounds are not
    // appended to a file that would then lack the lost one. Ended by the conversation's own drop.
    private val unwritten = HashSet<String>()

    @Volatile private var usable = files != null

    /** What the disk holds, read once before the cache serves anything; empty when nothing is kept. */
    fun restore(): List<StoredConversation> = synchronized(ioLock) {
        val stored = files?.restore()
        usable = stored != null
        stored.orEmpty()
    }

    /** Queue [key]'s admitted round. Called under the cache's lock; a key no file can carry is not queued. */
    fun append(key: String, toolIds: List<String>, envelopes: List<String>) {
        if (usable && files?.accepts(key) == true) {
            pending.add(ReasoningCacheWrite(key, StoredRound(toolIds, envelopes)))
        }
    }

    /** Queue the removal of [key]'s file. Called under the cache's lock. */
    fun drop(key: String) {
        if (usable && files?.accepts(key) == true) pending.add(ReasoningCacheWrite(key, round = null))
    }

    /** Apply every queued write, oldest first. Called with no cache lock held. */
    fun flush() {
        val disk = files ?: return
        synchronized(ioLock) {
            while (true) {
                val write = pending.poll() ?: break
                val round = write.round
                when {
                    round == null -> {
                        unwritten.remove(write.key)
                        disk.drop(write.key)
                    }
                    write.key in unwritten -> Unit
                    !disk.append(write.key, round) -> unwritten.add(write.key)
                }
            }
        }
    }
}

/** One queued write: append [round] to [key]'s conversation, or remove the conversation when it is null. */
internal data class ReasoningCacheWrite(val key: String, val round: StoredRound?)
