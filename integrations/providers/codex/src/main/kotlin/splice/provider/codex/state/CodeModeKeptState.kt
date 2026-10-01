// NEW: the durable cell index is updated only after a forced write, never rebuilt for a changed-cell append.
package splice.provider.codex.state

import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot

internal class CodeModeKeptState(state: CodeModePersistedState) {
    val records: MutableMap<String, CodeModeRecordSnapshot> =
        state.records.associateByTo(linkedMapOf(), CodeModeRecordSnapshot::id)
    val expired = state.expired

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
