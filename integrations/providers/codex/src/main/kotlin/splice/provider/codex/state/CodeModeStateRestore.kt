// NEW: byte-framed restore admits each entry without materializing a lookahead line, one native copy per slot.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapLines
import splice.core.memory.HeapText
import splice.provider.codex.CodeModeNativeSegment
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.CodeModeRecordSnapshot
import java.nio.file.Path

/** The source and complete checkpoint stay charged until current cells adopt independent reservations. */
internal object CodeModeStateRestore {
    fun read(path: Path, json: Json, heap: HeapBudget): CodeModePersistedState =
        HeapLines(path, heap).use { lines ->
            CodeModeCheckpointText(heap).use { checkpoint ->
                Entries(lines, checkpoint, json, heap, CodeModeStateJournal.endsWithNewline(path)).use { it.read() }
            }
        }

    private class Entries(
        private val lines: HeapLines,
        private val checkpoint: CodeModeCheckpointText,
        private val json: Json,
        private val heap: HeapBudget,
        private val committed: Boolean,
    ) : AutoCloseable {
        private var replay: CodeModeStateReplay? = null

        fun read(): CodeModePersistedState {
            while (lines.hasNext()) {
                val stage: HeapText = lines.next()
                stage.use { entry(it.text) }
            }
            return replay?.finish() ?: checkpoint.finish(json, committed)
        }

        private fun entry(text: String) {
            if (replay == null && !text.startsWith(CodeModeStateJournal.DELTA_START)) {
                checkpoint.append(text)
                return
            }
            val current = replay ?: CodeModeStateReplay(checkpoint.checkpoint(json), json, heap).also { replay = it }
            val tail = !lines.hasNext() && !committed
            if (!tail && text.isNotBlank()) current.apply(json.parseToJsonElement(text).jsonObject)
        }

        override fun close() {
            replay?.close()
        }
    }
}

/**
 * Before [CodeModeNativeChain.emittedReplay], each script's root record captured the reasoning that every
 * earlier owner had re-emitted at one slot, so the copies doubled per script and were persisted. On Oct 4 one
 * claudex conversation's journal held 38.7 MB of live cells: 18,140 native items with 218 distinct, and
 * 8,782 items with 19 distinct in its last root alone. Its live bytes were the file's own size, so the journal
 * could never outgrow them, and retention keeps a conversation in use.
 *
 * Upstream never produces one item twice, so a repeat at a slot is always a copy, the same rule the emitted
 * replay follows; the same bytes at another slot stay. The rewrite already posts each item once, so what a
 * record emits does not change. Dropping the copies as a journal decodes measures its live cells without
 * them, and a journal that held them is then outgrown and rewritten as one checkpoint at load.
 */
internal object CodeModeNativeCopies {
    fun dropped(state: CodeModePersistedState): CodeModePersistedState {
        val records = state.records.map(::dropped)
        return if (records.zip(state.records).all { (after, before) -> after === before }) {
            state
        } else {
            state.copy(records = records)
        }
    }

    private fun dropped(record: CodeModeRecordSnapshot): CodeModeRecordSnapshot {
        val seen = mutableMapOf<Int, MutableSet<JsonElement>>()
        val segments = record.nativeSegments.mapNotNull { segment ->
            val slot = seen.getOrPut(segment.logicalOffset) { mutableSetOf() }
            segment.copy(items = segment.items.filter(slot::add)).takeIf { it.items.isNotEmpty() }
        }
        val kept = segments.sumOf { it.items.size }
        if (kept == record.nativeSegments.sumOf { it.items.size }) return record
        return withNatives(record, segments)
    }

    // The fields outside the constructor are not carried by copy(); each is carried here by name.
    private fun withNatives(
        record: CodeModeRecordSnapshot,
        segments: List<CodeModeNativeSegment>,
    ): CodeModeRecordSnapshot = record.copy(nativeSegments = segments).also {
        it.issued = record.issued
        it.sessionId = record.sessionId
        it.conversationId = record.conversationId
        it.nativeBaseId = record.nativeBaseId
        it.replayAnchors = record.replayAnchors
        it.sourceState = record.sourceState
    }
}
