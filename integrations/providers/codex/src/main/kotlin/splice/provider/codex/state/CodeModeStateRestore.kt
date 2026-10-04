// NEW: byte-framed restore admits each entry without materializing a lookahead line.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import splice.core.memory.HeapBudget
import splice.core.memory.HeapLines
import splice.core.memory.HeapText
import splice.provider.codex.CodeModePersistedState
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
