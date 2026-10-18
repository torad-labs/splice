// NEW: journal entries assemble each cell's single encoding into patches or full recovery checkpoints.
package splice.provider.codex.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import splice.core.util.JsonWire
import splice.provider.codex.CodeModeExpiredSnapshot
import splice.provider.codex.CodeModePersistedState

/** The wire envelope stays independent of the durable index and generated field comparison. */
internal class CodeModeStateEncoding(private val json: Json) {
    fun patch(
        key: String,
        cells: List<CodeModeCellEncoding>,
        expired: List<CodeModeExpiredSnapshot>,
    ): String = buildString {
        append("{\"key\":")
        append(JsonPrimitive(key))
        append(",\"patches\":[")
        cells.forEachIndexed { index, cell ->
            if (index != 0) append(',')
            append(cell.patch())
        }
        append("],\"expired\":")
        append(JsonWire.string(json.encodeToJsonElement(expired)))
        append('}')
    }

    fun checkpoint(state: CodeModePersistedState, encoded: List<CodeModeCellEncoding>): String = buildString {
        val prepared = encoded.associateBy { it.snapshot.id }
        append("{\"version\":")
        append(state.version)
        append(",\"records\":[")
        state.records.forEachIndexed { index, record ->
            if (index != 0) append(',')
            append((prepared[record.id] ?: CodeModeCellEncoding(record, record, json)).full())
        }
        append("],\"expired\":")
        append(JsonWire.string(json.encodeToJsonElement(state.expired)))
        append('}')
    }
}
