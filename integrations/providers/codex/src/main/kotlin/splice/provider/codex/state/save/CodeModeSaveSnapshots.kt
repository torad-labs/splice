// NEW: immutable per-key save preparation runs under the registry's lock before serialization or I/O.
package splice.provider.codex.state.save

import splice.core.memory.HeapLease
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.CodeModeKeptState
import splice.provider.codex.state.CodeModeNativeChain
import splice.provider.codex.state.CodeModeStateJournal

/** A failed purge forces the complete current conversation, including when its changed record detached. */
internal class CodeModeSaveSnapshots(
    private val kept: Map<String, CodeModeKeptState>,
    private val uncertain: Set<String>,
    private val capacity: CodeModeSaveHeap,
) {
    class Prepared(
        val key: String,
        val conversation: CodeModePersistedState?,
        private val peak: HeapLease,
        val cells: List<Cell> = emptyList(),
        val nativeRoots: List<Cell> = emptyList(),
    ) : AutoCloseable {
        override fun close() {
            peak.close()
        }
    }

    data class Cell(val live: CodeModeRecord, val snapshot: CodeModeRecordSnapshot)

    fun prepare(
        key: String,
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        changedRecord: CodeModeRecord?,
    ): Prepared? {
        val indexed = kept[key]
        val cellsOnly = canPrepareCells(changedRecord, records, indexed)
        val peak = if (cellsOnly) {
            capacity.cells(checkNotNull(changedRecord), checkNotNull(indexed))
        } else {
            capacity.snapshot(records, expired, key, indexed)
        }
        var handedOff = false
        return try {
            val item = if (cellsOnly) {
                prepareCells(key, checkNotNull(indexed), checkNotNull(changedRecord), peak)
            } else {
                prepareConversation(key, records, expired, indexed, peak)
            }
            if (item != null) {
                capacity.retain(item, peak)
                handedOff = true
            }
            item
        } finally {
            if (!handedOff) peak.close()
        }
    }

    private fun prepareConversation(
        key: String,
        records: List<CodeModeRecord>,
        expired: List<CodeModeExpiredSnapshot>,
        indexed: CodeModeKeptState?,
        peak: HeapLease,
    ): Prepared? {
        val prior = indexed?.snapshot()
        val live = records.filter { it.key == key }
        val retained = live.map { it.id }.toSet()
        val snapshots = live.map { record ->
            record.saveGeneration++
            Cell(record, CodeModeNativeChain.snapshot(record, retained))
        }
        val nextRecords = snapshots.map { it.snapshot }
        val roots = snapshots.filter { it.live.nativeBaseId != it.snapshot.nativeBaseId }
        val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
        nextRecords.forEach { snapshot ->
            val old = before[snapshot.id]
            if (CodeModeStateJournal.same(snapshot, old)) snapshot.retainedBytes = old?.retainedBytes
        }
        val markers = expired.filter { it.key == key }
        val next = CodeModePersistedState(records = nextRecords, expired = markers)
            .takeUnless { it.records.isEmpty() && it.expired.isEmpty() }
        val changed = key in uncertain || next != prior || next?.records?.zip(prior?.records.orEmpty())
            ?.any { (left, right) -> !CodeModeStateJournal.same(left, right) } == true
        return if (changed) Prepared(key, next, peak, nativeRoots = roots) else null
    }

    private fun canPrepareCells(
        record: CodeModeRecord?,
        records: List<CodeModeRecord>,
        indexed: CodeModeKeptState?,
    ): Boolean {
        if (record == null || indexed == null) return false
        if (record.key in uncertain) return false
        return record in records && indexed.dirty.values.all { it in records }
    }

    private fun prepareCells(
        key: String,
        indexed: CodeModeKeptState,
        record: CodeModeRecord,
        peak: HeapLease,
    ): Prepared? {
        indexed.dirty[record.id] = record
        val cells = indexed.dirty.values.map { live ->
            live.saveGeneration++
            Cell(live, live.snapshot())
        }.filterNot { CodeModeStateJournal.same(it.snapshot, indexed.records[it.snapshot.id]) }
        if (cells.isEmpty()) {
            indexed.dirty.clear()
            return null
        }
        return Prepared(key, null, peak, cells)
    }
}
