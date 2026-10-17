// NEW: an atomic checkpoint uses the existing journal grammar with one full cell per entry.
package splice.provider.codex.state

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import splice.core.memory.HeapReservations
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.state.save.CodeModeSaveHeap
import java.io.OutputStream

/** Bounded entries also bound the initial checkpoint text and each replay decode after a restart. */
@OptIn(ExperimentalSerializationApi::class)
internal class CodeModeCheckpointEncoding(private val json: Json, private val heap: HeapReservations) {
    fun write(state: CodeModePersistedState, output: OutputStream) {
        val first = state.copy(
            records = state.records.take(1),
            expired = if (state.records.size <= 1) state.expired else emptyList(),
        )
        val capacity = CodeModeSaveHeap(heap)
        // The stream writer still copies a whole string into character scratch. Admit one cell, not the conversation.
        val initial: CodeModeSaveHeap.Encoding = capacity.full(first)
        initial.use {
            json.encodeToStream(CodeModePersistedState.serializer(), first, output)
            output.write('\n'.code)
        }
        for (index in 1 until state.records.size) {
            val record = state.records[index]
            val entry = CodeModeStateDelta(
                key = record.key,
                records = listOf(record),
                removed = emptySet(),
                expired = if (index == state.records.lastIndex) state.expired else emptyList(),
            )
            val stage: CodeModeSaveHeap.Encoding = capacity.encoding(entry.records, entry.expired)
            stage.use {
                json.encodeToStream(CodeModeStateDelta.serializer(), entry, output)
                output.write('\n'.code)
            }
        }
    }
}
