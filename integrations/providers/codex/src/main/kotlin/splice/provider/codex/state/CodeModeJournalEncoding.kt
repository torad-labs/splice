// NEW: changed-cell selection and journal entry encoding do not own disk I/O or durable indexes.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import splice.provider.codex.state.save.CodeModeSaveHeap
import splice.upstream.memory.JvmHeap
import java.nio.file.Path

internal object CodeModeJournalEncoding {
    fun same(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        left == right && left?.issued == right?.issued && left?.sessionId == right?.sessionId &&
            left?.conversationId == right?.conversationId &&
            left?.nativeBaseId == right?.nativeBaseId && left?.replayAnchors == right?.replayAnchors &&
            left?.sourceState == right?.sourceState

    /** Measures a loaded cell once; saves update only field sizes that actually changed. */
    fun liveBytes(
        state: CodeModePersistedState,
        json: Json,
        heap: HeapReservations,
    ): Long = state.records.sumOf { record ->
        if (record.encodedFieldBytes == null) {
            CodeModeSaveHeap(heap).encoding(listOf(record), emptyList()).use {
                CodeModeCellEncoding(record, null, json)
            }
        }
        checkNotNull(record.retainedBytes)
    }

    fun cellText(
        key: String,
        cells: List<CodeModeRecordSnapshot>,
        prior: CodeModeKeptState,
        json: Json,
        path: Path,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String {
        if (capacity == null) {
            return CodeModeSaveHeap(JvmHeap.budget).encoding(cells, prior.expired).use { peak ->
                cellText(key, cells, prior, json, path, peak).also(peak::retain)
            }
        }
        val encoded = cells.map { CodeModeCellEncoding(it, prior.records[it.id], json) }
        val encoding = CodeModeStateEncoding(json)
        return if (CodeModeStateJournal.appendable(path, prior.liveBytesWith(cells))) {
            encoding.patch(key, encoded, prior.expired)
        } else {
            // Changed fields were admitted first. The full envelope is admitted before its allocation.
            capacity.checkpoint(prior, cells)
            encoding.checkpoint(prior.withCells(cells), encoded)
        }
    }

    fun encode(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        json: Json,
        path: Path?,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String {
        if (capacity == null) {
            return CodeModeSaveHeap(JvmHeap.budget).full(next).use { peak ->
                encode(key, prior, next, json, path, peak).also(peak::retain)
            }
        }
        return admitted(key, prior, next, json, path)
    }

    private fun admitted(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        json: Json,
        path: Path?,
    ): String {
        val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
        val changed = next.records.filterNot { same(it, before[it.id]) && before[it.id]?.encodedFieldBytes != null }
        val encoded = changed.map { CodeModeCellEncoding(it, before[it.id], json) }
        next.records.filter { same(it, before[it.id]) && before[it.id]?.encodedFieldBytes != null }.forEach {
            it.retainedBytes = before[it.id]?.retainedBytes
            it.encodedFieldBytes = before[it.id]?.encodedFieldBytes
        }
        val removed = before.keys - next.records.map(CodeModeRecordSnapshot::id).toSet()
        val live = next.records.sumOf { checkNotNull(it.retainedBytes) }
        // Deletions compact so removed payloads cannot be restored from an earlier journal entry.
        val fullState = prior == null || removed.isNotEmpty()
        val cannotAppend = path?.let { !CodeModeStateJournal.appendable(it, live) } == true
        val encoding = CodeModeStateEncoding(json)
        return if (fullState || cannotAppend) {
            encoding.checkpoint(next, encoded)
        } else {
            encoding.patch(key, encoded, next.expired)
        }
    }
}
