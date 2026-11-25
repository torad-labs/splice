// NEW: modes-mcp (Nov 21, 2026) — the entry a client is given for a server this daemon answers at its `/mcp/<name>`.
// It was written inside McpSharing's plan, for a server the daemon hosts as a process. A server the daemon answers
// itself (a mode whose kind is mcp) is given the same entry by its launch, so the entry has one writer.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** The daemon's MCP address: [prefix] is `http://127.0.0.1:<port>/mcp/`, and [bearer] the MCP-scoped key as it stands
 *  when an entry is written. */
public class McpEndpoint(private val prefix: String, private val bearer: McpBearer) {
    /** The entry for the server [name]: its address, the bearer ([token], read now when none is handed in), the head
     *  the client is on, and, for a server the daemon answers itself, who the client is ([caller]). Reading the access
     *  key throws IOException when it cannot be read. */
    public fun entry(name: String, headKey: String?, token: String = bearer(), caller: String? = null): JsonObject =
        buildJsonObject {
            put("type", "http")
            put("url", prefix + name)
            putJsonObject("headers") {
                put("Authorization", "Bearer $token")
                headKey?.takeIf { it.isNotEmpty() }?.let { put(MCP_HEAD_HEADER, it) }
                caller?.let { put(MCP_CALLER_HEADER, it) }
            }
        }
}
