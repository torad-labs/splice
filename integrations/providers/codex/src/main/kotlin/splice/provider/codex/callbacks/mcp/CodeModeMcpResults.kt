package splice.provider.codex.callbacks.mcp

import kotlinx.serialization.json.JsonObject

/** Reads an actual MCP reply only after its issued callback id, tool name and arguments match. */
public fun interface CodeModeMcpResults {
    public fun read(callbackId: String, name: String, arguments: JsonObject): JsonObject?
}
