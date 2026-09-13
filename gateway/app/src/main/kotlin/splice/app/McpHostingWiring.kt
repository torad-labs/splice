// NEW (v0.4.0, FEATURES.md §8): the app-side glue for shared MCP hosting — the [daemon] knobs as
// a value the control plane can take without seeing the topology, and the tolerant read of the
// operator's ~/.claude.json that the planner and the host both consult (never cached: an edit to
// the operator's servers takes effect at the next spawn).
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import splice.control.mcp.GlobalMcpServers
import splice.core.topology.DaemonConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal data class McpHostingSettings(
    val enabled: Boolean = true,
    val exclude: Set<String> = emptySet(),
) {
    /** The topology's own words: absent `mcp_hosting` means on; the exclude list is optional. */
    internal fun with(daemon: DaemonConfig): McpHostingSettings =
        McpHostingSettings(daemon.mcpHosting ?: true, daemon.mcpHostingExclude?.toSet() ?: emptySet())
}

/** `mcpServers` from `<home>/.claude.json`; absent, unreadable or malformed reads as no servers,
 *  matching the materializer's tolerant global read — a bad operator file never fails a launch. */
internal class McpGlobalRead(private val home: Path) : GlobalMcpServers {
    private val json = Json { ignoreUnknownKeys = true }

    override fun invoke(): JsonObject {
        val file = home.resolve(".claude.json")
        val text = try {
            Files.readString(file)
        } catch (_: IOException) {
            return buildJsonObject {}
        }
        val parsed = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
        return parsed?.get("mcpServers") as? JsonObject ?: buildJsonObject {}
    }
}
