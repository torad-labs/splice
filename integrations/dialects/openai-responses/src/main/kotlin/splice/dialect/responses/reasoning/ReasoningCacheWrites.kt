// NEW: V4-334 — the reasoning cache's disk writes, applied BEHIND its state lock and in the order the cache
// decided them. The cache decides under its monitor (admit a round, evict a conversation, end one at its
// compaction) and only queues the write here, so the queue's order is the order of the changes: a conversation
// evicted and begun again is deleted before its new round is appended.
//
// REWORKED 2026-10-26 (fix 3, approved by Marcos about 2:00 AM CT): the writes leave the thread that made the
// change. That thread used to drain the queue itself, under one lock every session on the head shared, forcing each
// record to disk; a completed reasoning item makes its change inside the upstream read loop, so a session's stream
// waited on the disk for its own records and every other session's (claudex, Oct 25, 6 to 11 PM CT: splice's first
// byte to the client trailed the upstream's by 6.4 s at p50 and 29.7 s at p90). Now the thread only wakes the head's
// one writer and goes on. The writer applies the queue oldest first, so the order above holds whichever thread
// queued, and the head's stop waits for it ([drain]). A record lands in the page cache, which outlives the process,
// so a restart that stops the head keeps every record. A process killed outright loses what was still queued, and a
// power loss what the kernel had not yet written out (ReasoningCacheFiles.force says what that costs).
package splice.dialect.responses.reasoning

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class ReasoningCacheWrites(private val files: ReasoningCacheFiles?) {
    private val pending = ConcurrentLinkedQueue<ReasoningCacheWrite>()

    // A pass is queued that has not yet begun to read [pending]: a wake meanwhile is already covered by it.
    private val woken = AtomicBoolean()

    // The head's one writer. One thread, so the writes apply in queue order; named, so a stack dump says which lane
    // waits on the disk; a daemon thread, so a write never holds a dying JVM (the stop's drain is what lands them);
    // and none at all while idle, so a cache nobody writes to holds no thread.
    private val threads = Executors.defaultThreadFactory()
    private val writer =
        ThreadPoolExecutor(0, 1, WRITER_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
            threads.newThread(task).apply {
                name = "splice-reasoning-cache"
                isDaemon = true
            }
        }

    // Conversations whose append failed: their file is gone, and later rounds are not appended to a file that would
    // then lack the lost one. Ended by the conversation's own drop. Read and written on the writer's thread.
    private val unwritten: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var usable = files != null

    /** What the disk holds, read once before the cache serves anything, so before any write is queued; empty when
     *  nothing is kept. */
    fun restore(): List<StoredConversation> {
        val stored = files?.restore()
        usable = stored != null
        return stored.orEmpty()
    }

    /** Queue [key]'s admitted round. Called under the cache's lock; a key no file can carry is not queued. */
    fun append(
        key: String,
        toolIds: List<String>,
        envelopes: List<String>,
        resultProofs: Map<String, String?> = emptyMap(),
    ) {
        if (usable && files?.accepts(key) == true) {
            pending.add(ReasoningCacheWrite(key, StoredRound(toolIds, envelopes, resultProofs)))
        }
    }

    /** A reconciled conversation replaces its old records atomically, retaining the installed schema. */
    fun replace(key: String, rounds: List<StoredRound>) {
        if (usable && files?.accepts(key) == true) {
            pending.add(ReasoningCacheWrite(key, round = null, replacement = rounds))
        }
    }

    /** Queue the removal of [key]'s file. Called under the cache's lock. */
    fun drop(key: String) {
        if (usable && files?.accepts(key) == true) pending.add(ReasoningCacheWrite(key, round = null))
    }

    /** Hand every write queued so far to the writer and return at once: the caller never waits on the disk. Called
     *  with no cache lock held. */
    fun wake() {
        if (usable && woken.compareAndSet(false, true)) writer.execute(::writeQueued)
    }

    /** Wait until every write queued before this call is on disk, at most [timeoutMs]. False when the time ran out
     *  (or the wait was interrupted) with writes still queued: they land if the process lives on. */
    fun drain(timeoutMs: Long): Boolean {
        if (!usable) return true
        val landed = CountDownLatch(1)
        writer.execute {
            try {
                writeQueued()
            } finally {
                landed.countDown()
            }
        }
        return try {
            landed.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** Apply every queued write, oldest first. Runs on the writer's thread only. */
    private fun writeQueued() {
        // Cleared before the queue is read, so a write queued from here on wakes a pass that reads after it.
        woken.set(false)
        val disk = files ?: return
        while (true) {
            val write = pending.poll() ?: break
            applyWrite(disk, write)
        }
    }

    private fun applyWrite(disk: ReasoningCacheFiles, write: ReasoningCacheWrite) {
        val round = write.round
        val replacement = write.replacement
        when {
            round == null && replacement == null -> {
                unwritten.remove(write.key)
                disk.drop(write.key)
            }
            write.key in unwritten -> Unit
            replacement != null -> if (!disk.replace(write.key, replacement)) unwritten.add(write.key)
            round != null && !disk.append(write.key, round) -> unwritten.add(write.key)
        }
    }
}

/** One queued write: append [round] to [key]'s conversation, or remove the conversation when it is null. */
internal data class ReasoningCacheWrite(
    val key: String,
    val round: StoredRound?,
    val replacement: List<StoredRound>? = null,
)

// How long the writer's thread outlives its last write: a head in use keeps its one thread, an idle head keeps none.
private const val WRITER_IDLE_SECONDS = 10L
