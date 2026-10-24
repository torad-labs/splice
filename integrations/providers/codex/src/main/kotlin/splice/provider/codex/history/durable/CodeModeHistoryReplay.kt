package splice.provider.codex.history.durable

import splice.core.memory.HeapReservations
import splice.core.memory.HeapText
import splice.provider.codex.history.CodeModeLedgerEntry
import java.io.IOException

/** Revisions form an acyclic persistent graph; completed output updates a post without changing its stable id. */
internal class CodeModeHistoryReplay(private val key: String, private val heap: HeapReservations) {
    private val revisions = mutableMapOf<Long, CodeModeLedgerEntry>()
    private val posts = mutableMapOf<Long, CodeModeLedgerEntry>()
    private var revision = 0L
    private var nextId = 1L
    private var memoryBytes = 0L
    var lastDigest: String? = null
        private set
    var committedBytes = 0L
        private set

    fun accept(delta: CodeModeHistoryDelta, stage: HeapText) {
        val identity = delta.version == 1 && delta.key == key && delta.revision == revision + 1L
        val post = delta.post
        val monotonic = post.id > 0 && delta.nextId > post.id && delta.nextId >= nextId
        if (!identity || !monotonic) throw IOException("authoritative history revision identity is invalid")
        val parent = post.base?.let { revisions[it] ?: throw IOException("authoritative history parent is absent") }
        val entry = CodeModeHistoryDeltas.apply(delta, parent)
        CodeModeHistoryDeltas.own(CodeModeHistoryPrepared(delta, entry), heap)
        revisions[delta.revision] = entry
        posts[entry.id] = entry
        revision = delta.revision
        nextId = delta.nextId
        memoryBytes += CodeModeHistoryDeltas.weight(delta)
        lastDigest = splice.core.perf.InputDigest.hex(stage.text)
        committedBytes += splice.provider.codex.state.CodeModeStateText(stage.text).bytes + 1L
    }

    fun finish(): CodeModeHistoryState = CodeModeHistoryState(
        key,
        posts.values.sortedByDescending { it.id },
        nextId,
        revision = revision,
        memoryBytes = memoryBytes,
    )
}

internal const val HISTORY_DECODE_FACTOR = 16L
