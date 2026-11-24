// NEW: desktop-antigravity (docs/specs/desktop-antigravity.md). The ACP requests splice sends agy, built in one place:
// asking agy what it offers (AgyModels) and running a Desktop session on it (DesktopAcp) open the same way. splice
// names itself in `clientInfo` as Google's terms ask, and declines the file system and the terminal, so agy uses its
// own.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The parameters of each ACP request splice sends. */
internal class AcpRequests {
    fun initialize(): JsonObject = buildJsonObject {
        put("protocolVersion", 1)
        putJsonObject("clientCapabilities") {
            putJsonObject("fs") {
                put("readTextFile", false)
                put("writeTextFile", false)
            }
            put("terminal", false)
        }
        putJsonObject("clientInfo") {
            put("name", "splice")
            put("title", "Splice for Claude Desktop")
        }
    }

    /** A new session in [folder] with [mcpServers], [append] added to agy's system prompt when given (agy's
     *  `_meta.antigravity.systemPrompt.append`). Only a new session carries it: agy adds an append given on resume to
     *  the one the session already has. */
    fun newSession(
        folder: String,
        mcpServers: JsonArray = JsonArray(emptyList()),
        append: String? = null,
    ): JsonObject = buildJsonObject {
        put("cwd", folder)
        put("mcpServers", mcpServers)
        if (append != null) {
            putJsonObject("_meta") {
                putJsonObject("antigravity") {
                    putJsonObject("systemPrompt") { put("append", append) }
                }
            }
        }
    }

    /** Session [id] taken up again in [folder] with [mcpServers]. */
    fun resume(id: String, folder: String, mcpServers: JsonArray = JsonArray(emptyList())): JsonObject =
        buildJsonObject {
            put("sessionId", id)
            put("cwd", folder)
            put("mcpServers", mcpServers)
        }

    fun sessionOf(id: String): JsonObject = buildJsonObject { put("sessionId", id) }
}
