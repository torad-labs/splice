// NEW: the disk writer streams checkpoints; injected text writers retain their existing boundary.
package splice.provider.codex.state.save

import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.state.CodeModeJournalText
import splice.provider.codex.state.CodeModeStateJournal
import java.nio.file.Path

/** One conversation's forced write. Text-only adapters still see complete checkpoint contents. */
internal fun interface CodeModeStateWrite {
    fun write(path: Path, text: String)

    fun checkpoint(path: Path, state: CodeModePersistedState, json: Json, heap: HeapReservations) {
        CodeModeSaveHeap(heap).full(state).use { peak ->
            val text = CodeModeJournalText(json).encode("", null, state, capacity = peak)
            peak.retain(text)
            write(path, text)
        }
    }
}

/** Production checkpoints never allocate a conversation-sized string or UTF-8 byte array. */
internal object CodeModeJournalWrite : CodeModeStateWrite {
    override fun write(path: Path, text: String) = CodeModeStateJournal.write(path, text)

    override fun checkpoint(path: Path, state: CodeModePersistedState, json: Json, heap: HeapReservations) {
        CodeModeStateJournal.checkpoint(path, state, heap)
    }
}
