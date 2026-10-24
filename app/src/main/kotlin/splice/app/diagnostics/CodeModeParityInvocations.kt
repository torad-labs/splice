package splice.app.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.upstream.codemode.CodeModeCall

internal object CodeModeParityInvocations {
    fun call(held: CodeModeParityCell, call: CodeModeCall): JsonObject = buildJsonObject {
        put("type", "tool/invoke")
        put(
            "invocation",
            buildJsonObject {
                put("cell_id", held.cell.cellId); put("runtime_tool_call_id", call.id)
                put("tool_name", buildJsonObject { put("name", call.name); put("namespace", JsonNull) })
                put("tool_kind", "function"); put("input", call.arguments)
            },
        )
    }
    fun notification(held: CodeModeParityCell, text: String): JsonObject = buildJsonObject {
        put("type", "notification/send"); put("callId", "diagnostic")
        put("cellId", held.cell.cellId); put("text", text)
    }
}
