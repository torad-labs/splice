// NEW: V4-457 — chat tool definitions and call arguments share one encoding boundary.
package splice.dialect.chat

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolUseBlock

internal class ChatToolInput {
    fun appendCalls(sink: JsonArrayBuilder, toolUses: List<ToolUseBlock>, texts: String) {
        sink.addJsonObject {
            put("role", "assistant")
            if (texts.isNotEmpty()) put(CONTENT, texts) else put(CONTENT, null as String?)
            put(
                "tool_calls",
                buildJsonArray {
                    toolUses.forEach { tool ->
                        addJsonObject {
                            put("id", tool.id)
                            put(TYPE, FUNCTION)
                            putFunction(this, tool.name, JsonWire.string(tool.input))
                        }
                    }
                },
            )
        }
    }

    fun definitions(body: AnthropicRequest) = buildJsonArray {
        body.tools.forEach { tool ->
            addJsonObject {
                put(TYPE, FUNCTION)
                put(
                    FUNCTION,
                    buildJsonObject {
                        put(NAME, tool.name)
                        put("description", tool.description ?: "")
                        put("parameters", tool.inputSchema ?: buildJsonObject { put(TYPE, "object") })
                    },
                )
            }
        }
    }

    // Name and encoded arguments are both Strings; this boundary keeps their wire fields paired.
    private fun putFunction(sink: JsonObjectBuilder, name: String, args: String) {
        sink.put(
            FUNCTION,
            buildJsonObject {
                put(NAME, name)
                put("arguments", args)
            },
        )
    }
}
