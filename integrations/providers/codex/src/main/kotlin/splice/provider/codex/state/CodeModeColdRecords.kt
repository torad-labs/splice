package splice.provider.codex.state

import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModeRecordSnapshot
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
            throw CodeModePersistenceException(failure, diskFull = false)
        } catch (failure: IllegalArgumentException) {
            throw CodeModePersistenceException(IOException("Invalid cold code-mode journal", failure), diskFull = false)
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
