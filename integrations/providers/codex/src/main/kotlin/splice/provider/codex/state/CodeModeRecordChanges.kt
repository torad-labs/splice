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
 * source reader stages prefixes under that key (CodeModeSourceRecords), and a client-visible boundary
 * snapshots the whole batch while holding it. The driver once added tool calls outside that key, so a
 * snapshot's iteration met the add and ConcurrentModificationException killed the source reader. Source,
 * result and driver mutations now share the key; only the visible boundary performs the durable save.
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
