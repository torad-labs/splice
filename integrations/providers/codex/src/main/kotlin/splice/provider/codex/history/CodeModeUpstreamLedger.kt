package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.history.durable.CodeModeHistoryCheckpoint
import splice.provider.codex.history.durable.CodeModeHistoryDeltas
import splice.provider.codex.history.durable.CodeModeHistoryPrepared
import splice.provider.codex.history.durable.CodeModeHistoryState
import splice.provider.codex.history.durable.CodeModeHistoryStorage
import splice.provider.codex.history.notification.CodeModeNotificationInputs

/** Exact model input and the client prefix it acknowledged. Echoes are delivery receipts, never model authority. */
@Serializable
internal data class CodeModeLedgerEntry(
    val covered: List<JsonElement>,
    val sent: List<JsonElement>,
    val echoes: List<JsonElement>,
    val id: Long = 0,
    val outputRecorded: Boolean = false,
    val callbacks: Map<String, CodeModeCallbackReceipt> = emptyMap(),
    val revision: Long = 0,
)

/**
 * The map is a byte-bounded cache. Durable revisions retain every branch address after eviction or restart.
 *
 * With journaling off the map is the only copy, and until Nov 9, 2026 it was never bounded: the eviction stopped short
 * of it, so a long uptime kept every conversation's every post. It is bounded now too, by bytes only: a conversation
 * past half the budget keeps its newest posts within a quarter of it ([CodeModeHistoryCheckpoint]), and the least
 * recently used conversations go once the whole map is past it. What goes is what a restart loses in that mode: its
 * next request is folded from the client's history, as a new conversation's is.
 */
internal class CodeModeUpstreamLedger(
    private val capacity: Int = LEDGER_CONVERSATIONS,
    private val storage: CodeModeHistoryStorage? = null,
    private val cacheBytes: Long = LEDGER_CACHE_BYTES,
) {
    // Striped, so the locks stay bounded however many conversations the head has seen.
    private val keyLocks = Array(LEDGER_LOCK_STRIPES) { Any() }
    private val entries = LinkedHashMap<String, CodeModeHistoryState>(LEDGER_INITIAL, LEDGER_LOAD, true)

    private fun lock(key: String): Any = keyLocks[Math.floorMod(key.hashCode(), LEDGER_LOCK_STRIPES)]

    fun entries(key: String): List<CodeModeLedgerEntry> = synchronized(lock(key)) {
        state(key)?.posts.orEmpty()
    }

    /** The conversations held in memory now: the ones a sweep or a Settings clear must leave on the disk. */
    @Synchronized
    fun hot(): Set<String> = entries.keys.toHashSet()

    fun newest(key: String): Long? = entries(key).firstOrNull()?.id

    /** Records [entry] as [key]'s newest post and returns its id, which the request that posted it keeps. */
    fun record(key: String, entry: CodeModeLedgerEntry, baseId: Long? = null): Long =
        synchronized(lock(key)) {
            val prior = state(key)
            val id = prior?.nextId ?: 1L
            val revision = (prior?.revision ?: 0L) + 1L
            val addressed = baseId?.let { target -> prior?.posts?.firstOrNull { it.id == target } }
            val base = addressed ?: prior?.posts?.firstOrNull()
            val state = CodeModeHistoryState(key, emptyList(), id + 1L, revision = revision)
            val prepared = CodeModeHistoryDeltas.prepare(state, entry.copy(id = id), base)
            val posts = listOf(prepared.entry) + prior?.posts.orEmpty()
            publish(state.copy(posts = posts), prepared, prior?.memoryBytes ?: 0L)
            id
        }

    fun output(key: String, id: Long, output: List<JsonElement>, echoes: List<JsonElement>) =
        synchronized(lock(key)) {
            val prior = state(key) ?: return@synchronized
            val base = prior.posts.firstOrNull { it.id == id } ?: return@synchronized
            if (base.outputRecorded) return@synchronized
            val next = base.copy(sent = base.sent + output, echoes = base.echoes + echoes, outputRecorded = true)
            val state = prior.copy(revision = prior.revision + 1L)
            val prepared = CodeModeHistoryDeltas.prepare(state, next, base)
            val posts = prior.posts.map { if (it.id == id) prepared.entry else it }
            publish(state.copy(posts = posts), prepared, prior.memoryBytes)
        }

    /** Accepted asynchronous input extends this exact branch without finishing or replacing its model response. */
    fun inject(key: String, id: Long, record: CodeModeRecord) =
        synchronized(lock(key)) {
            val prior = state(key) ?: return@synchronized
            val base = prior.posts.firstOrNull { it.id == id } ?: return@synchronized
            val missing = CodeModeNotificationInputs.missing(base.sent, listOf(record))
            if (missing.isEmpty()) return@synchronized
            val next = base.copy(
                sent = base.sent + missing,
                echoes = base.echoes + CodeModeNotificationInputs.echoes(missing),
            )
            val state = prior.copy(revision = prior.revision + 1L)
            val prepared = CodeModeHistoryDeltas.prepare(state, next, base)
            val posts = prior.posts.map { if (it.id == id) prepared.entry else it }
            publish(state.copy(posts = posts), prepared, prior.memoryBytes)
        }

    /** Exceptional requests release hot owners without erasing accepted model history. */
    @Synchronized
    fun forget(key: String) {
        entries.remove(key)
    }

    private fun state(key: String): CodeModeHistoryState? = synchronized(this) { entries[key] }
        ?: storage?.read(key)?.also(::cache)

    /** The storage keeps [prepared] and may hand back the conversation's newest posts once its journal was rewritten;
     *  the post [prepared] made or changed stays among them. */
    private fun publish(state: CodeModeHistoryState, prepared: CodeModeHistoryPrepared, inheritedBytes: Long) {
        val next = state.copy(memoryBytes = inheritedBytes + CodeModeHistoryDeltas.weight(prepared.delta))
        cache(storage?.write(prepared, next) ?: next, prepared.entry.id)
    }

    @Synchronized
    private fun cache(state: CodeModeHistoryState, written: Long? = null) {
        val durable = storage != null && storage.durableEnabled
        entries[state.key] = if (durable || written == null) state else bounded(state, written)
        evict(durable, state.key)
    }

    /** Lets the least recently used conversations go until the cache fits: by count and bytes when journals hold
     *  them, by bytes alone in memory, where [current] (the only copy of its newest posts, already bounded) stays. */
    private fun evict(durable: Boolean, current: String) {
        var bytes = entries.values.sumOf { it.memoryBytes }
        val oldest = entries.entries.iterator()
        val count = if (durable) capacity else Int.MAX_VALUE
        var fits = entries.size <= count && bytes <= cacheBytes
        while (!fits && oldest.hasNext()) {
            val evicted = oldest.next()
            if (durable || evicted.key != current) {
                bytes -= evicted.value.memoryBytes
                oldest.remove()
                storage?.forget(evicted.key)
            }
            fits = entries.size <= count && bytes <= cacheBytes
        }
    }

    /** With no journal behind it, a conversation past half the budget keeps its newest posts within a quarter of it,
     *  the post just [written] among them. Revisions stay as they were: the first save once journaling is back finds
     *  the disk behind and rewrites the journal whole. */
    private fun bounded(state: CodeModeHistoryState, written: Long): CodeModeHistoryState {
        if (state.memoryBytes <= cacheBytes / LEDGER_BOUND_PARTS * 2L) return state
        val kept = CodeModeHistoryCheckpoint.select(state, written, cacheBytes / LEDGER_BOUND_PARTS)
        return state.copy(posts = kept.posts, memoryBytes = kept.weight)
    }
}

// Hot-cache count only; every branch remains in the durable journal.
private const val LEDGER_CONVERSATIONS = 32

// Initial map allocation stays small before the cache grows.
private const val LEDGER_INITIAL = 16

// Standard hash load factor balances collisions against hot metadata overhead.
private const val LEDGER_LOAD = 0.75f

// Default hot graph budget; durable authority survives every cache eviction.
private const val LEDGER_CACHE_BYTES = 256L * 1024 * 1024

// Memory-only conversations are trimmed past two quarters of the budget, down to one quarter.
private const val LEDGER_BOUND_PARTS = 4L

// Lock stripes for conversation keys: enough that unrelated conversations rarely share one.
private const val LEDGER_LOCK_STRIPES = 64
