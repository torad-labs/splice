// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md). Tracks active MCP servers for a Gemini session:
// their connection status, `system/init` listing, `mcp_status` responses, `mcp_set_servers` diffing, and mapping
// agy's `mcp_<server>_<tool>` calls to Claude Code's `mcp__<server>__<tool>` tool names.
package splice.provider.antigravity

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** Maps an ACP MCP tool name (`mcp_<server>_<tool>`) to Claude Code's `mcp__<server>__<tool>` when known. */
internal fun interface McpToolLookup {
    fun claudeName(rawName: String): String?
}

/** Result of reconciling a `mcp_set_servers` request against what agy already started. */
internal data class McpSetDiff(
    val added: List<AgyMcpSpec>,
    val dropped: List<String>,
    val nextAll: List<AgyMcpSpec>,
)

/** Live MCP server registry for one Desktop session on agy. */
internal class McpState : McpToolLookup {
    private val servers = LinkedHashMap<String, AgyMcpSpec>()
    private val connected: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var applied: Boolean = false
        private set

    fun setInitial(specs: List<AgyMcpSpec>, wasApplied: Boolean) {
        servers.clear()
        applied = wasApplied && specs.isNotEmpty()
        if (applied) {
            specs.forEach { servers[it.name] = it }
        }
    }

    fun markConnected(name: String) {
        connected.add(name)
    }

    fun diffForSet(requested: List<AgyMcpSpec>): McpSetDiff {
        val wantedByName = requested.associateBy { it.name }
        val added = requested.filter { it.name !in servers }
        val dropped = servers.keys.filter { it !in wantedByName }
        val nextAll = ArrayList<AgyMcpSpec>(servers.values.size + added.size)
        nextAll.addAll(servers.values)
        nextAll.addAll(added)
        return McpSetDiff(added, dropped, nextAll)
    }

    fun commitAdded(added: List<AgyMcpSpec>) {
        if (added.isEmpty()) return
        added.forEach { servers[it.name] = it }
        applied = true
    }

    fun initServers(): JsonArray = buildJsonArray {
        servers.keys.forEach { name ->
            add(
                buildJsonObject {
                    put("name", name)
                    put("status", statusOf(name))
                },
            )
        }
    }

    fun statusResponse(): JsonObject = buildJsonObject {
        put("mcpServers", initServers())
    }

    fun setServersResponse(added: List<String>, dropped: List<String>, addError: String?): JsonObject =
        buildJsonObject {
            put("added", JsonArray(if (addError == null) added.map(::JsonPrimitive) else emptyList()))
            put("removed", JsonArray(emptyList()))
            put(
                "errors",
                buildJsonObject {
                    if (addError != null) added.forEach { put(it, addError) }
                    dropped.forEach { put(it, CANNOT_DROP_MESSAGE) }
                },
            )
        }

    override fun claudeName(rawName: String): String? {
        if (!rawName.startsWith(ACP_MCP_PREFIX)) return null
        val rest = rawName.removePrefix(ACP_MCP_PREFIX)
        val matched = servers.keys.sortedByDescending { it.length }.firstOrNull { name ->
            rest.startsWith("${name}_") || rest.startsWith("${name.replace('-', '_')}_")
        }
        val prefix = matched?.let { if (rest.startsWith("${it}_")) "${it}_" else "${it.replace('-', '_')}_" }
        val tool = prefix?.let { rest.removePrefix(it) }.orEmpty()
        return if (matched != null && tool.isNotEmpty()) {
            connected.add(matched)
            "mcp__${matched}__$tool"
        } else {
            null
        }
    }

    private fun statusOf(name: String): String = if (name in connected) "connected" else "pending"
}

private const val ACP_MCP_PREFIX = "mcp_"
private const val CANNOT_DROP_MESSAGE = "agy cannot remove an MCP server after the session started"
