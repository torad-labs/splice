package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Exact model input and the client prefix it acknowledged. Echoes are delivery receipts, never model authority. */
@Serializable
internal data class CodeModeLedgerEntry(
    val covered: List<JsonElement>,
    val sent: List<JsonElement>,
    val echoes: List<JsonElement>,
    val id: Long = 0,
    val outputRecorded: Boolean = false,
)

/** The bounded map is a cache only. Eviction and daemon restart reload every durable branch, including side posts. */
internal class CodeModeUpstreamLedger(
    private val capacity: Int = LEDGER_CONVERSATIONS,
    private val storage: CodeModeHistoryStorage? = null,
) {
    private val entries = object : LinkedHashMap<String, CodeModeHistoryState>(LEDGER_INITIAL, LEDGER_LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CodeModeHistoryState>?): Boolean =
            storage != null && size > capacity
    }

    @Synchronized
    fun entries(key: String): List<CodeModeLedgerEntry> = state(key)?.posts.orEmpty()

    @Synchronized
    fun newest(key: String): Long? = state(key)?.posts?.firstOrNull()?.id

    /** Every distinct branch remains addressable on disk. Reposting the same acknowledged prefix supersedes it. */
    @Synchronized
    fun record(key: String, entry: CodeModeLedgerEntry) {
        val prior = state(key)
        val id = prior?.nextId ?: 1L
        val posts = prior?.posts.orEmpty().filterNot { it.covered == entry.covered }
        val next = CodeModeHistoryState(key, listOf(entry.copy(id = id)) + posts, id + 1L)
        publish(next)
    }

    /** A response extends its exact input; encrypted reasoning and original native message items stay verbatim. */
    @Synchronized
    fun output(key: String, id: Long, output: List<JsonElement>, echoes: List<JsonElement>) {
        val prior = state(key) ?: return
        val posts = prior.posts.map { entry ->
            if (entry.id != id || entry.outputRecorded) {
                entry
            } else {
                entry.copy(
                    sent = entry.sent + output,
                    echoes = entry.echoes + echoes,
                    outputRecorded = true,
                )
            }
        }
        if (posts != prior.posts) publish(prior.copy(posts = posts))
    }

    /** Drop hot references only. An exceptional request cannot erase already accepted durable model history. */
    @Synchronized
    fun forget(key: String) {
        entries.remove(key)
    }

    private fun state(key: String): CodeModeHistoryState? = entries[key]
        ?: storage?.read(key)?.also { entries[key] = it }

    private fun publish(state: CodeModeHistoryState) {
        storage?.write(state)
        entries[state.key] = state
    }
}

private const val LEDGER_CONVERSATIONS = 32
private const val LEDGER_INITIAL = 16
private const val LEDGER_LOAD = 0.75f
