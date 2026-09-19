// NEW: v0.4.0 FEATURES.md §8 — the app-side glue for shared MCP hosting — the [daemon] knobs as
// a value the control plane can take without seeing the topology, and the tolerant read of the
// operator's ~/.claude.json that the planner and the host both consult (never cached: an edit to
// the operator's servers takes effect at the next spawn).
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import splice.control.mcp.GlobalMcpServers
import splice.core.topology.DaemonConfig
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
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
internal class McpGlobalRead(
    private val home: Path,
    private val log: LogSink = LogSink(System.err::print),
) : GlobalMcpServers {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastFailure: String? = null

    @Synchronized
    override fun invoke(): JsonObject {
        val file = home.resolve(".claude.json")
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            val present = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
            return empty(if (present) "global MCP configuration is unreadable" else null)
        } catch (_: IOException) {
            return empty("global MCP configuration is unreadable")
        }
        val parsed = parse(text)
        val servers = parsed?.get("mcpServers") as? JsonObject
        return when {
            parsed == null -> empty("global MCP configuration is malformed")
            servers != null -> servers.also { lastFailure = null }
            parsed.containsKey("mcpServers") -> empty("global MCP server map is malformed")
            else -> empty(null)
        }
    }

    private fun parse(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun empty(reason: String?): JsonObject {
        if (reason != null && reason != lastFailure) {
            log("[mcp-host] $reason; server discovery unavailable (file content withheld)\n")
        }
        lastFailure = reason
        return buildJsonObject {}
    }
}
