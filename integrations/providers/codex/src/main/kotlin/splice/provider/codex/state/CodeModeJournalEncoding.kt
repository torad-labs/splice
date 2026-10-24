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
        left == right && left?.completionError == right?.completionError &&
            left?.issued == right?.issued && left?.sessionId == right?.sessionId &&
            left?.conversationId == right?.conversationId &&
            left?.nativeBaseId == right?.nativeBaseId && left?.postedOn == right?.postedOn &&
            left?.replayAnchors == right?.replayAnchors &&
            sourceSame(left, right)

    private fun sourceSame(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        left?.sourceState == right?.sourceState && left?.nativeAfterExec == right?.nativeAfterExec &&
            left?.nativeCellId == right?.nativeCellId && left?.nativeOutput == right?.nativeOutput &&
            nativeDeliverySame(left, right)

    private fun nativeDeliverySame(left: CodeModeRecordSnapshot?, right: CodeModeRecordSnapshot?): Boolean =
        left?.deliveredOnly == right?.deliveredOnly && left?.nativeNotifications == right?.nativeNotifications &&
            left?.nativeCompleted == right?.nativeCompleted

    fun unchanged(record: CodeModeRecordSnapshot, prior: CodeModeRecordSnapshot?): Boolean =
        same(record, prior) && prior?.encodedFieldBytes != null

    /** Measures a loaded cell once; saves update only field sizes that actually changed. */
    fun liveBytes(
        state: CodeModePersistedState,
        json: Json,
        heap: HeapReservations,
    ): Long = state.records.sumOf { record ->
        if (record.encodedFieldBytes == null) {
            val stage: CodeModeSaveHeap.Encoding = CodeModeSaveHeap(heap).encoding(listOf(record), emptyList())
            stage.use { CodeModeCellEncoding(record, null, json) }
        }
        checkNotNull(record.retainedBytes)
    }

    /** Checkpoints measure fields through a counting stream, never by retaining every cell's encoding. */
    fun measure(state: CodeModePersistedState, prior: CodeModePersistedState?, json: Json, heap: HeapReservations) {
        val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
        val capacity = CodeModeSaveHeap(heap)
        capacity.streaming().use {
            state.records.forEach { record ->
                val old = before[record.id]
                if (unchanged(record, old)) {
                    record.retainedBytes = old?.retainedBytes
                    record.encodedFieldBytes = old?.encodedFieldBytes
                    return@forEach
                }
                val stage: CodeModeSaveHeap.Encoding = capacity.encoding(listOf(record), emptyList())
                stage.use { CodeModeCellEncoding(record, old, json) }
            }
        }
    }
}

/** Journal text for one conversation, encoded with [json]: a patch of changed cells or a full checkpoint. Each call
 *  admits its own encoding peak unless the caller holds one as [CodeModeSaveHeap.Encoding]. */
internal class CodeModeJournalText(private val json: Json) {
    fun cellText(
        key: String,
        cells: List<CodeModeRecordSnapshot>,
        prior: CodeModeKeptState,
        path: Path,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String {
        if (capacity == null) {
            return CodeModeSaveHeap(JvmHeap.budget).encoding(cells, prior.expired).use { peak ->
                cellText(key, cells, prior, path, peak).also(peak::retain)
            }
        }
        cellPatch(key, cells, prior, path)?.let { return it }
        capacity.checkpoint(prior, cells)
        return CodeModeStateEncoding(json).checkpoint(prior.withCells(cells), emptyList())
    }

    /** Null asks the store for a streamed checkpoint, without allocating its full text first. */
    fun cellPatch(key: String, cells: List<CodeModeRecordSnapshot>, prior: CodeModeKeptState, path: Path): String? {
        val encoded = cells.map { CodeModeCellEncoding(it, prior.records[it.id], json) }
        return if (CodeModeStateJournal.appendable(path, prior.liveBytesWith(cells))) {
            CodeModeStateEncoding(json).patch(key, encoded, prior.expired)
        } else {
            null
        }
    }

    fun encode(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        path: Path? = null,
        capacity: CodeModeSaveHeap.Encoding? = null,
    ): String {
        if (capacity == null) {
            return CodeModeSaveHeap(JvmHeap.budget).conversation(next, prior).use { peak ->
                encode(key, prior, next, path, peak).also(peak::retain)
            }
        }
        return admitted(key, prior, next, path, capacity)
    }

    private fun admitted(
        key: String,
        prior: CodeModePersistedState?,
        next: CodeModePersistedState,
        path: Path?,
        capacity: CodeModeSaveHeap.Encoding,
    ): String {
        val before = prior?.records.orEmpty().associateBy(CodeModeRecordSnapshot::id)
        val unchanged = { record: CodeModeRecordSnapshot ->
            CodeModeJournalEncoding.unchanged(record, before[record.id])
        }
        val changed = next.records.filterNot(unchanged)
        val encoded = changed.map { CodeModeCellEncoding(it, before[it.id], json) }
        next.records.filter(unchanged).forEach {
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
            capacity.checkpoint(next)
            encoding.checkpoint(next, encoded)
        } else {
            encoding.patch(key, encoded, next.expired)
        }
    }
}
