// NEW: per conversation, the exact histories code mode last sent GPT, which the next request only extends.
package splice.provider.codex.history

import kotlinx.serialization.json.JsonElement

/**
 * What one upstream post was: the client's conversation it answered ([covered]), the conversation it sent
 * ([sent]), and the items it sent past [covered] that the client replays at the start of what it adds next
 * ([echoes]): the reasoning and preface of a script that made no client call. [id] tells the posts apart.
 */
internal data class CodeModeLedgerEntry(
    val covered: List<JsonElement>,
    val sent: List<JsonElement>,
    val echoes: List<JsonElement>,
    val id: Long = 0,
)

/**
 * Codex builds each request from the items it recorded (codex-rs core/src/session/turn.rs:1618), so what it
 * sent is always a prefix of what it sends next. This keeps the same for code mode, in memory, for the most
 * recently posted [capacity] conversations. Each keeps its last [depth] posts, newest first, so a side request
 * Claude Code sends on the same conversation (a compaction summary, a quick question) does not take the main
 * history's place. A conversation it no longer holds falls back to the client's history once, and is kept again
 * from that post.
 */
internal class CodeModeUpstreamLedger(
    private val capacity: Int = LEDGER_CONVERSATIONS,
    private val depth: Int = LEDGER_POSTS,
) {
    private var nextId = 1L
    private val entries = object : LinkedHashMap<String, ArrayDeque<CodeModeLedgerEntry>>(
        LEDGER_INITIAL,
        LEDGER_LOAD,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArrayDeque<CodeModeLedgerEntry>>?) =
            size > capacity
    }

    /** The conversation's kept posts, newest first. */
    @Synchronized
    fun entries(key: String): List<CodeModeLedgerEntry> = entries[key]?.toList().orEmpty()

    /** The id of the conversation's newest kept post, the one a script started now answers. */
    @Synchronized
    fun newest(key: String): Long? = entries[key]?.firstOrNull()?.id

    /** Keeps [entry] as the newest post. A post that answered the same client history as the newest one (a
     *  script's output sent in the request that started it) takes its place, so only the fullest one is kept. */
    @Synchronized
    fun record(key: String, entry: CodeModeLedgerEntry) {
        val posts = entries.getOrPut(key) { ArrayDeque() }
        if (posts.firstOrNull()?.covered == entry.covered) posts.removeFirst()
        posts.addFirst(CodeModeLedgerEntry(entry.covered, entry.sent, entry.echoes, nextId++))
        while (posts.size > depth) posts.removeLast()
    }

    @Synchronized
    fun forget(key: String) {
        entries.remove(key)
    }
}

// why: one conversation per seat or subagent talking to GPT at once; a conversation past it falls back once.
private const val LEDGER_CONVERSATIONS = 32

// why: the main history plus the side requests Claude Code sends on the same conversation (a summary, a quick
// question) between two of its turns.
private const val LEDGER_POSTS = 4

// why: the LinkedHashMap defaults, spelled out because its access-order constructor takes all three arguments.
private const val LEDGER_INITIAL = 16

// why: the LinkedHashMap default load factor, for the same reason.
private const val LEDGER_LOAD = 0.75f
