package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

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

/** The map is a byte-bounded cache. Durable revisions retain every branch address after eviction or restart. */
internal class CodeModeUpstreamLedger(
    private val capacity: Int = LEDGER_CONVERSATIONS,
    private val storage: CodeModeHistoryStorage? = null,
    private val cacheBytes: Long = LEDGER_CACHE_BYTES,
) {
    private val keyLocks = ConcurrentHashMap<String, Any>()
    private val entries = LinkedHashMap<String, CodeModeHistoryState>(LEDGER_INITIAL, LEDGER_LOAD, true)

    fun entries(key: String): List<CodeModeLedgerEntry> = synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
        state(key)?.posts.orEmpty()
    }

    fun newest(key: String): Long? = entries(key).firstOrNull()?.id

    fun record(key: String, entry: CodeModeLedgerEntry, baseId: Long? = null) =
        synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
            val prior = state(key)
            val id = prior?.nextId ?: 1L
            val revision = (prior?.revision ?: 0L) + 1L
            val addressed = baseId?.let { target -> prior?.posts?.firstOrNull { it.id == target } }
            val base = addressed ?: prior?.posts?.firstOrNull()
            val state = CodeModeHistoryState(key, emptyList(), id + 1L, revision = revision)
            val prepared = CodeModeHistoryDeltas.prepare(state, entry.copy(id = id), base)
            val posts = listOf(prepared.entry) + prior?.posts.orEmpty()
            publish(state.copy(posts = posts), prepared, prior?.memoryBytes ?: 0L)
        }

    fun output(key: String, id: Long, output: List<JsonElement>, echoes: List<JsonElement>) =
        synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
            val prior = state(key) ?: return@synchronized
            val base = prior.posts.firstOrNull { it.id == id } ?: return@synchronized
            if (base.outputRecorded) return@synchronized
            val next = base.copy(sent = base.sent + output, echoes = base.echoes + echoes, outputRecorded = true)
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

    private fun publish(state: CodeModeHistoryState, prepared: CodeModeHistoryPrepared, inheritedBytes: Long) {
        storage?.write(prepared)
        cache(
            state.copy(
                memoryBytes = inheritedBytes + CodeModeHistoryDeltas.weight(prepared.delta),
            ),
        )
    }

    @Synchronized
    private fun cache(state: CodeModeHistoryState) {
        entries[state.key] = state
        if (storage == null || !storage.durableEnabled) return
        var bytes = entries.values.sumOf { it.memoryBytes }
        val oldest = entries.entries.iterator()
        var oversized = entries.size > capacity || bytes > cacheBytes
        while (oldest.hasNext() && oversized) {
            bytes -= oldest.next().value.memoryBytes
            oldest.remove()
            oversized = entries.size > capacity || bytes > cacheBytes
        }
    }
}

private const val LEDGER_CONVERSATIONS = 32
private const val LEDGER_INITIAL = 16
private const val LEDGER_LOAD = 0.75f
private const val LEDGER_CACHE_BYTES = 256L * 1024 * 1024
