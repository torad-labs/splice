// NEW: desktop-antigravity (door chat). JSON-RPC 2.0 encoding helpers for [DoorToolBridge]'s
// virtual loopback HTTP MCP server.
package splice.provider.antigravity.door

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.core.util.JsonScalars

internal object DoorToolRpc {
    fun initialize(params: JsonObject): JsonObject {
        val version = JsonScalars.str(params, "protocolVersion") ?: DEFAULT_MCP_PROTOCOL
        return buildJsonObject {
            put("protocolVersion", version)
            putJsonObject("capabilities") { putJsonObject("tools") {} }
            putJsonObject("serverInfo") {
                put("name", "splice-chat-tools")
                put("version", "1.0.0")
            }
        }
    }

    fun toolsList(tools: List<AnthropicToolDef>): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                tools.forEach { tool ->
                    add(
                        buildJsonObject {
                            put("name", tool.name)
                            if (tool.description.isNotEmpty()) put("description", tool.description)
                            put("inputSchema", tool.inputSchema)
                        },
                    )
                }
            },
        )
    }

    fun toolContent(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put(
            "content",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                ),
            ),
        )
        if (isError) put("isError", true)
    }

    fun result(rpcId: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", rpcId)
        put("result", result)
    }
}

private const val DEFAULT_MCP_PROTOCOL = "2024-11-05"
