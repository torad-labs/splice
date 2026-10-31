package splice.app.diagnostics

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                put(
                    "tool_name",
                    buildJsonObject {
                        put("name", call.originalName)
                        put("namespace", call.namespace?.let(::JsonPrimitive) ?: JsonNull)
                    },
                )
                put("tool_kind", call.kind.name.lowercase())
                put("input_present", call.rawInput != null)
                put("input", call.rawInput ?: call.arguments)
            },
        )
    }
    fun notification(held: CodeModeParityCell, text: String): JsonObject = buildJsonObject {
        put("type", "notification/send"); put("callId", "diagnostic")
        put("cellId", held.cell.cellId); put("text", text)
    }
}
