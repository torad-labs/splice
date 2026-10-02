// NEW: the script driver's changes to a live code-mode record are made under the record's conversation key.
package splice.provider.codex.state

import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodexCodeModeStore

/** A change to a live record, run under its conversation's key. */
internal fun interface CodeModeRecordChange {
    operator fun invoke(record: CodeModeRecord)
}

/**
 * Every change the script's driver makes to a live record, made under the record's conversation key. The
 * source reader snapshots a record under that key each time it saves a streamed script delta
 * (CodeModeSourceRecords). The driver added its tool calls outside it, so the snapshot's iteration met the
 * add, and the ConcurrentModificationException killed the reader with the script's source unended: on
 * Oct 2, under load, a session's script waited forever.
 */
internal class CodeModeRecordChanges(
    private val access: CodeModeRegistryAccess,
    private val records: List<CodeModeRecord>,
    private val history: CodeModeExpiredHistory,
    private val store: CodexCodeModeStore,
) {
    /** A retry's ownership read shares the driver's key, without sweeping or ending any source. */
    fun clientIds(record: CodeModeRecord): Set<String> = access.withKey(record.key) { record.clientIds() }

    /** A change the record's next save carries. */
    fun edit(record: CodeModeRecord, change: CodeModeRecordChange) = access.withKey(record.key) { change(record) }

    /** A change saved with its conversation under the same hold; [undo] runs when the save fails. */
    fun save(
        record: CodeModeRecord,
        undo: CodeModeRecordChange = CodeModeRecordChange {},
        change: CodeModeRecordChange,
    ) = access.withKey(record.key) {
        change(record)
        try {
            store.save(records, history.entries, dirtyKeys = setOf(record.key), changedRecord = record)
        } catch (error: CodeModePersistenceException) {
            undo(record)
            throw error
        }
    }
}
