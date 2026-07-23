// NEW: Anthropic tool_choice → OpenAI tool_choice. ONE mapper for both OpenAI-family dialects — Chat
// and Responses carried byte-identical private copies (the v29 copies-drift class; review
// 2026-07-22): auto/none map straight through, Anthropic "any" is OpenAI "required", and a named
// tool becomes {"type":"function","function":{"name":...}}. Absent or unrecognized → "auto".
package splice.core.wire

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public fun openAiToolChoice(choice: ToolChoice?): JsonElement = when {
    choice?.type == "none" -> JsonPrimitive("none")
    choice?.type == "any" -> JsonPrimitive("required")
    choice?.type == "tool" && choice.name != null -> buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject { put("name", choice.name) })
    }
    else -> JsonPrimitive("auto") // null / "auto" / unrecognized types
}
