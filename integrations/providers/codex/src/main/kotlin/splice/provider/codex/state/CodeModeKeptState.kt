// NEW: the durable cell index is updated only after a forced write, never rebuilt for a changed-cell append.
package splice.provider.codex.state

import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot

internal class CodeModeKeptState(state: CodeModePersistedState) {
    private val byId: MutableMap<String, CodeModeRecordSnapshot> =
        state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
    val records: Map<String, CodeModeRecordSnapshot> get() = byId
    val expired = state.expired

    /** The encoded bytes of [records], kept as cells land so a cell write never re-measures its conversation.
     *  Every kept record was measured: the store measures each one it reads or writes whole. */
    var liveBytes: Long = byId.values.sumOf { it.retainedBytes ?: 0L }
        private set

    /** Records [written] cells after their forced write. */
    fun put(written: List<CodeModeRecordSnapshot>) {
        liveBytes = liveBytesWith(written)
        written.forEach { byId[it.id] = it }
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
