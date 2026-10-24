package splice.provider.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal object CodeModeWaitTool {
    fun declaration(): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", CODE_MODE_WAIT_TOOL_NAME)
        put(
            "description",
            "Wait for a running exec cell and return only its new output. Set terminate to stop that cell.",
        )
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("cell_id") { put("type", "string") }
                putJsonObject("yield_time_ms") { put("type", "integer"); put("minimum", 0) }
                putJsonObject("max_tokens") { put("type", "integer"); put("minimum", 0) }
                putJsonObject("terminate") { put("type", "boolean") }
            }
            put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("cell_id")) })
            put("additionalProperties", false)
        }
    }
}
