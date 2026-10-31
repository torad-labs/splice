package splice.provider.codex.callbacks.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.upstream.codemode.json.CodeModeJson
import splice.upstream.codemode.json.CodeModeJsonException

/** The resolved MCP error value used by Codex 0.165, after an owned JSON-RPC error response. */
public object CodeModeMcpRpcErrors {
    private val integer = Regex("-?(0|[1-9][0-9]*)")

    /** Invalid ErrorData remains absent; a valid failure resolves as CallToolResult with isError=true. */
    public fun result(server: String, tool: String, error: JsonObject): JsonObject? {
        val number = code(error) ?: return null
        val message = (error["message"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return data(error)?.let { suffix -> envelope(server, tool, "Mcp error: $number: $message$suffix") }
    }

    private fun code(error: JsonObject): Int? = (error["code"] as? JsonPrimitive)
        ?.takeUnless { it.isString || !integer.matches(it.content) }?.content?.toIntOrNull()

    private fun envelope(server: String, tool: String, cause: String): JsonObject {
        // rmcp ErrorData/ServiceError Display, transparent ClientOperationError, then anyhow's single-cause Debug.
        val text = "tool call error: tool call failed for `$server/$tool`\n\nCaused by:\n    " +
            cause.replace("\n", "\n    ")
        return buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                },
            )
            put("isError", true)
        }
    }

    private fun data(error: JsonObject): String? {
        val value = error["data"]?.takeUnless { it is JsonNull } ?: return ""
        return try {
            "(${JsonWire.string(CodeModeJson.normalize(value))})"
        } catch (_: CodeModeJsonException) {
            null
        }
    }
}
