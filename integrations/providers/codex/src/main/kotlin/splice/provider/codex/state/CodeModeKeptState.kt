// NEW: the durable cell index is updated only after a forced write, never rebuilt for a changed-cell append.
package splice.provider.codex.state

import splice.core.memory.HeapBudget
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapOwners
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.upstream.memory.JvmHeap

// why: keyed index references, map nodes and backing-table growth beyond the separately owned snapshot.
private const val KEPT_INDEX_ENTRY_BYTES = 128L

internal class CodeModeKeptState(state: CodeModePersistedState, private val heap: HeapBudget = JvmHeap.budget) {
    init {
        state.records.forEach { CodeModeHeap.own(it, heap) }
        state.expired.forEach { CodeModeHeap.own(it, heap) }
    }
    private val byId: MutableMap<String, CodeModeRecordSnapshot> =
        state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
    private val indexLease = HeapOwners.charge(byId, heap, (state.records.size + 1L) * KEPT_INDEX_ENTRY_BYTES)
    val records: Map<String, CodeModeRecordSnapshot> get() = byId
    val expired = state.expired

    /** The encoded bytes of [records], kept as cells land so a cell write never re-measures its conversation.
     *  Every kept record was measured: the store measures each one it reads or writes whole. */
    var liveBytes: Long = byId.values.sumOf { it.retainedBytes ?: 0L }
        private set

    /** Admit durable index growth before the force. A failed force keeps the existing high-water owner. */
    fun prepare(written: List<CodeModeRecordSnapshot>) {
        written.forEach { CodeModeHeap.own(it, heap) }
        val added = written.count { it.id !in byId }
        val required = (byId.size + added + 1L) * KEPT_INDEX_ENTRY_BYTES
        if (!indexLease.resize(maxOf(indexLease.bytes, required))) throw HeapCapacityException()
    }

    /** Records [written] cells after their forced write. */
    fun put(written: List<CodeModeRecordSnapshot>) {
        prepare(written)
        liveBytes = liveBytesWith(written)
        written.forEach {
            CodeModeHeap.own(it, heap)
            byId[it.id] = it
        }
    }

    /** [liveBytes] once [changed] cells replace the ones of the same id. */
    fun liveBytesWith(changed: List<CodeModeRecordSnapshot>): Long =
        liveBytes + changed.sumOf { (it.retainedBytes ?: 0L) - (byId[it.id]?.retainedBytes ?: 0L) }

    /** Failed saves keep live references, so a rolled-back acceptance is never resurrected on retry. */
    val dirty: MutableMap<String, CodeModeRecord> = linkedMapOf()
    private val version = state.version

    /** Full materialization is for load, deletion compaction and missing-file recovery only. */
    fun snapshot(): CodeModePersistedState =
        CodeModePersistedState(version, records.values.toList(), expired)

    fun withCells(cells: List<CodeModeRecordSnapshot>): CodeModePersistedState {
        val next = LinkedHashMap(records)
        cells.forEach { next[it.id] = it }
        return CodeModePersistedState(version, next.values.toList(), expired)
    }
}

// UTF-8 uses one byte below U+0080, two below U+0800, and three for the remaining BMP scalars.
private const val ASCII_CHAR_LIMIT = 0x80

// UTF-8's two-byte form represents scalar values below U+0800.
private const val TWO_BYTE_CHAR_LIMIT = 0x800

// A non-surrogate BMP scalar at or above U+0800 takes three UTF-8 bytes.
private const val BMP_CHAR_BYTES = 3

// A paired UTF-16 surrogate represents one supplementary scalar, encoded as four UTF-8 bytes.
private const val SURROGATE_PAIR_BYTES = 4

/** Encoded JSON and its UTF-8 size, without allocating a byte copy just to measure it. */
internal class CodeModeStateText(val text: String) {
    val bytes: Long = count()

    private fun count(): Long {
        var bytes = 0L
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            bytes += when {
                char.code < ASCII_CHAR_LIMIT -> 1
                char.code < TWO_BYTE_CHAR_LIMIT -> 2
                char.isHighSurrogate() && index < text.length && text[index].isLowSurrogate() -> {
                    index++
                    SURROGATE_PAIR_BYTES
                }
                char.isSurrogate() -> 1 // JVM UTF-8 replaces an unpaired surrogate with '?'.
                else -> BMP_CHAR_BYTES
            }
        }
        return bytes
    }
}
