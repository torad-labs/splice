package splice.provider.codex.history

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import splice.core.memory.HeapCategory
import splice.core.memory.HeapOwners
import splice.core.memory.HeapReservations
import splice.provider.codex.state.CodeModeWeight

@Serializable
internal data class CodeModeHistoryDelta(
    val key: String,
    val revision: Long,
    val nextId: Long,
    val post: CodeModeHistoryPostDelta,
    val version: Int = 1,
)

@Serializable
internal data class CodeModeHistoryPostDelta(
    val id: Long,
    val base: Long?,
    val covered: CodeModeHistoryTail,
    val sent: CodeModeHistoryTail,
    val delivery: CodeModeHistoryDelivery,
    val callbacks: CodeModeHistoryCallbackPatch,
)

@Serializable
internal data class CodeModeHistoryTail(val prefix: Int, val items: List<JsonElement>)

@Serializable
internal data class CodeModeHistoryDelivery(val echoes: List<JsonElement>, val outputRecorded: Boolean)

@Serializable
internal data class CodeModeHistoryCallbackPatch(
    val changed: Map<String, CodeModeCallbackReceipt>,
    val removed: Set<String>,
)

internal data class CodeModeHistoryPrepared(val delta: CodeModeHistoryDelta, val entry: CodeModeLedgerEntry)

/** Journal and hot graph both retain only the newly owned suffix, even when callers pass a full request array. */
internal object CodeModeHistoryDeltas {
    fun prepare(
        state: CodeModeHistoryState,
        entry: CodeModeLedgerEntry,
        base: CodeModeLedgerEntry?,
    ): CodeModeHistoryPrepared {
        val covered = tail(base?.covered.orEmpty(), entry.covered)
        val sent = tail(base?.sent.orEmpty(), entry.sent)
        val callbacks = CodeModeHistoryCallbackPatch(
            entry.callbacks.filter { (id, receipt) -> base?.callbacks?.get(id) != receipt },
            base?.callbacks.orEmpty().keys - entry.callbacks.keys,
        )
        val post = CodeModeHistoryPostDelta(
            entry.id,
            base?.revision,
            covered,
            sent,
            CodeModeHistoryDelivery(entry.echoes, entry.outputRecorded),
            callbacks,
        )
        val delta = CodeModeHistoryDelta(state.key, state.revision, state.nextId, post)
        return CodeModeHistoryPrepared(delta, apply(delta, base))
    }

    fun apply(delta: CodeModeHistoryDelta, base: CodeModeLedgerEntry?): CodeModeLedgerEntry {
        val post = delta.post
        require(post.base == base?.revision) { "History revision parent is unavailable" }
        return CodeModeLedgerEntry(
            covered = HistoryItemTrees.splice(base?.covered.orEmpty(), post.covered.prefix, post.covered.items),
            sent = HistoryItemTrees.splice(base?.sent.orEmpty(), post.sent.prefix, post.sent.items),
            echoes = post.delivery.echoes,
            id = post.id,
            outputRecorded = post.delivery.outputRecorded,
            callbacks = HistoryCallbackTrees.patch(
                base?.callbacks.orEmpty(),
                post.callbacks.changed,
                post.callbacks.removed,
            ),
            revision = delta.revision,
        )
    }

    fun weight(delta: CodeModeHistoryDelta): Long {
        val post = delta.post
        val stored = CodeModeWeight.STORED
        val payload = post.covered.items.sumOf(stored::json) + post.sent.items.sumOf(stored::json) +
            post.delivery.echoes.sumOf(stored::json)
        val callbacks = post.callbacks.changed.entries.sumOf { (id, receipt) ->
            stored.text(id) + stored.text(receipt.callDigest) + stored.text(receipt.outputDigest) +
                receipt.media.sumOf(stored::json)
        }
        val width = maxOf(post.covered.prefix + post.covered.items.size, post.sent.prefix + post.sent.items.size)
        val height = Integer.SIZE - Integer.numberOfLeadingZeros(width.coerceAtLeast(1))
        return payload + callbacks + DELTA_METADATA_BYTES + height * DELTA_TREE_LEVEL_BYTES *
            (post.callbacks.changed.size + post.callbacks.removed.size + DELTA_ITEM_TREES)
    }

    fun own(prepared: CodeModeHistoryPrepared, heap: HeapReservations) {
        val lease = HeapOwners.charge(prepared.entry, heap, weight(prepared.delta), HeapCategory.CODE_MODE)
        payloads(prepared.delta).forEach { HeapOwners.keep(it, lease.share()) }
    }

    fun payloads(delta: CodeModeHistoryDelta): List<Any> = buildList {
        addAll(delta.post.covered.items)
        addAll(delta.post.sent.items)
        addAll(delta.post.delivery.echoes)
        delta.post.callbacks.changed.values.forEach { receipt -> add(receipt); addAll(receipt.media) }
    }

    private fun tail(base: List<JsonElement>, next: List<JsonElement>): CodeModeHistoryTail {
        val previous = base.iterator()
        val current = next.iterator()
        var prefix = 0
        var matching = true
        while (previous.hasNext() && current.hasNext()) {
            if (!matching) break
            val old = previous.next()
            val item = current.next()
            matching = old === item || old == item
            if (matching) prefix++
        }
        return CodeModeHistoryTail(prefix, next.drop(prefix))
    }
}

private const val DELTA_METADATA_BYTES = 512L
private const val DELTA_TREE_LEVEL_BYTES = 128L
private const val DELTA_ITEM_TREES = 2
