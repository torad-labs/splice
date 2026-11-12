// NEW: an atomic checkpoint uses the existing journal grammar with one full cell per entry.
package splice.provider.codex.state.save

import kotlinx.serialization.json.Json
import splice.core.memory.HeapReservations
import splice.core.util.JsonWire
import splice.provider.codex.CodeModePersistedState
import splice.provider.codex.state.CodeModeStateDelta
import java.io.OutputStream

/** Bounded entries also bound the initial checkpoint text and each replay decode after a restart. */
internal class CodeModeCheckpointEncoding(private val json: Json, private val heap: HeapReservations) {
    fun write(state: CodeModePersistedState, output: OutputStream) {
        val first = state.copy(
            records = state.records.take(1),
            expired = if (state.records.size <= 1) state.expired else emptyList(),
        )
        val capacity = CodeModeSaveHeap(heap)
        val wire = JsonWire.encoder(output)
        // Generate one cell's envelope while borrowing its payload nodes. The bounded writer never
        // retains whole-string character scratch in the serializer's process-wide buffer pool.
        val initial: CodeModeSaveHeap.Encoding = capacity.full(first)
        initial.use {
            wire.write(json.encodeToJsonElement(CodeModePersistedState.serializer(), first))
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
                wire.write(json.encodeToJsonElement(CodeModeStateDelta.serializer(), entry))
                output.write('\n'.code)
            }
        }
    }
}
