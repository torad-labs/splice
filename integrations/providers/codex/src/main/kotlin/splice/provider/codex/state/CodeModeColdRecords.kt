package splice.provider.codex.state

import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModePersistenceException
import splice.provider.codex.CodeModePhase
import splice.provider.codex.CodeModeRecord
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.CodeModeStorageException
import splice.provider.codex.CodeModeStorageOperation
import splice.provider.codex.CodeModeStorageProblem
import splice.provider.codex.state.letgo.CodeModeColdEntry
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** A small directory of journal-backed records, with no retained payload snapshots. */
internal class CodeModeColdRecords(
    private val files: CodeModeStateDirectory,
    private val kept: MutableMap<String, CodeModeKeptState>,
    private val monitor: ReentrantLock?,
    private val heap: HeapReservations,
    /** The store's keys whose journal and kept copy may not agree: a write failed, or is under way. */
    private val unsettled: List<Set<String>> = emptyList(),
) {
    private val entries = ConcurrentHashMap<String, CodeModeColdEntry>()
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
        entries[key] = kept[key]?.let(::entry) ?: return false
        kept.remove(key)
        return true
    }

    /** #525 (2026-11-13): the conversations the retiring daemon told this start of. Their records wait in their
     *  journals, unread, for the turn that restores them; their expiry markers are returned for the registry's
     *  history, as a start that reads a journal returns them. */
    fun told(conversations: Map<String, CodeModeColdEntry>): List<CodeModeExpiredSnapshot> {
        entries.putAll(conversations)
        return conversations.values.flatMap(CodeModeColdEntry::expired)
    }

    /** #525: the conversations this store has let go at the retire whose journals it knows whole, for the list the
     *  next daemon's start reads: the cold ones, and each kept one whose records are all completed, which is what a
     *  start keeps on the disk alone. Asked once the write scope has retired. No write of such a conversation begins
     *  after that, and one still under way has its key in one of [unsettled] until its journal and the copy kept
     *  here agree. The registry's monitor makes that key visible here, and the keys are read before the copies, so a
     *  write that ends in between leaves its conversation out. */
    fun letGo(): Map<String, CodeModeColdEntry> {
        monitor?.lock()
        try {
            val open = unsettled.flatMapTo(HashSet()) { it }
            val known = entries + kept.mapNotNull { (key, state) ->
                entry(state)?.takeIf { state.records.values.all { it.phase == CodeModePhase.COMPLETED } }
                    ?.let { key to it }
            }
            return known.filterKeys { !files.writes.allows(it) } - open
        } finally {
            monitor?.unlock()
        }
    }

    /** What is kept of [state] once its records leave memory; null when it holds no record. */
    private fun entry(state: CodeModeKeptState): CodeModeColdEntry? {
        val records = state.records.values
        val updatedAt = records.maxOfOrNull(CodeModeRecordSnapshot::updatedAt) ?: return null
        return CodeModeColdEntry(
            updatedAt,
            state.liveBytes,
            records.mapNotNullTo(mutableSetOf()) { it.conversationId },
            state.expired,
        )
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

    /** #525 (2026-11-12): a conversation another daemon held at boot, read from its journal now that the hold is free,
     *  as the boot reads one: its records wait here, cold, for the turn that restores them, and its expiry markers
     *  are returned for the registry's history. A conversation with no journal yet has neither. */
    fun adopt(key: String): List<CodeModeExpiredSnapshot> {
        val state = try {
            files.reload(key)
        } catch (failure: IOException) {
            throw CodeModePersistenceException(failure, diskFull = false, CodeModeStorageOperation.RESTORE)
        } catch (failure: IllegalArgumentException) {
            throw CodeModePersistenceException(
                CodeModeStorageException.Invalid(CodeModeStorageProblem.COLD_STATE, failure),
                diskFull = false,
                CodeModeStorageOperation.RESTORE,
            )
        } ?: return emptyList()
        kept[key] = CodeModeKeptState(state.version, state, heap)
        if (state.records.isNotEmpty()) evict(key)
        return state.expired
    }

    /** Publish only after the registry has successfully materialized every live record. */
    fun restored(key: String, state: CodeModePersistedState) {
        kept[key] = CodeModeKeptState(state.version, state, heap)
        entries.remove(key)
    }
}
