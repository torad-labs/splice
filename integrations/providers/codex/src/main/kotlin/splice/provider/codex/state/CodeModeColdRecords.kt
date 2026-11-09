package splice.provider.codex.state

import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeStorageException
import splice.provider.codex.CodeModeStorageOperation
import splice.provider.codex.CodeModeStorageProblem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** A small directory of journal-backed records, with no retained payload snapshots. */
internal class CodeModeColdRecords(
    private val files: CodeModeStateDirectory,
    private val kept: MutableMap<String, CodeModeKeptState>,
    private val monitor: ReentrantLock?,
    private val heap: HeapReservations,
) {
    private data class Entry(val updatedAt: Long, val conversations: Set<String>)
    private val entries = ConcurrentHashMap<String, Entry>()
    val keys: Set<String> get() = entries.keys.toSet()
    val retained: Boolean get() = entries.isNotEmpty()

    operator fun contains(key: String): Boolean = entries.containsKey(key)
    fun before(cutoff: Long): Set<String> = entries.filterValues { it.updatedAt < cutoff }.keys
    fun forConversation(id: String): Set<String> = entries.filterValues { id in it.conversations }.keys
    fun clear() = entries.clear()
    fun committed(key: String): CodeModeKeptState? = kept[key]

    /** An unwritten mutation invalidates its cached size; expired keys must release their old snapshot owners. */
    fun changedWithoutJournal(keys: Set<String>, records: List<CodeModeRecord>) {
        records.filter { it.key in keys }.forEach { it.retainedBytes = null }
        val liveKeys = records.mapTo(HashSet(), CodeModeRecord::key)
        (keys - liveKeys).forEach(kept::remove)
    }

    /** The caller has committed and owns this key. Remove the store's snapshot owner too. */
    fun evict(key: String): Boolean {
        val records = kept[key]?.records?.values.orEmpty()
        val updatedAt = records.maxOfOrNull(CodeModeRecordSnapshot::updatedAt) ?: return false
        entries[key] = Entry(updatedAt, records.mapNotNullTo(mutableSetOf()) { it.conversationId })
        kept.remove(key)
        return true
    }

    /** Under the key lock and registry monitor. A read failure is never a replay cache miss. */
    fun restore(key: String): CodeModePersistedState? {
        if (!entries.containsKey(key)) return null
        monitor?.unlock()
        return try {
            files.restore(key)
        } catch (failure: IOException) {
            throw CodeModePersistenceException(failure, diskFull = false, CodeModeStorageOperation.RESTORE)
        } catch (failure: IllegalArgumentException) {
            throw CodeModePersistenceException(
                CodeModeStorageException.Invalid(CodeModeStorageProblem.COLD_STATE, failure),
                diskFull = false,
                CodeModeStorageOperation.RESTORE,
            )
        } finally {
            monitor?.lock()
        }
    }

    /** Publish only after the registry has successfully materialized every live record. */
    fun restored(key: String, state: CodeModePersistedState) {
        kept[key] = CodeModeKeptState(state.version, state, heap)
        entries.remove(key)
    }
}
