// NEW: v0.4.0 FEATURES.md §8 — the app-side glue for shared MCP hosting — the [daemon] knobs as
// a value the control plane can take without seeing the topology, and the tolerant read of the
// operator's ~/.claude.json that the planner and the host both consult (never cached: an edit to
// the operator's servers takes effect at the next spawn).
//
// V4-146 (2026-09-20): McpInventoryWiring at the bottom of this file is the production wiring for
// the five-kind census (splice.client.mcp's McpSources.kt / McpInventory) — real filesystem roots
// around the SAME McpGlobalRead and McpSharing this file already builds, so the census can never
// disagree with what the real pipeline did for the canonical home.
package splice.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import splice.client.mcp.GlobalMcpServersReader
import splice.client.mcp.McpGlobalPlan
import splice.client.mcp.McpInventory
import splice.client.mcp.McpSharing
import splice.client.mcp.McpSourceKind
import splice.client.mcp.PluginInlineReader
import splice.client.mcp.PluginMcpJsonReader
import splice.client.mcp.ProjectMcpServersReader
import splice.client.mcp.RepoMcpJsonReader
import splice.control.mcp.GlobalMcpServers
import splice.core.topology.DaemonConfig
import splice.core.util.LogSink
import splice.launch.LaunchSpec
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

/** V4-146: production wiring for the five-kind MCP census. Built around the SAME [home], [sharing]
 *  and [global] the caller already constructed for the real hosting pipeline — the census asks
 *  McpSharing for the canonical home's actual plan rather than re-deriving eligibility, so it can
 *  never disagree with what the pipeline did; the four other readers only WIDEN what else is
 *  visible around that one rewritten file (this file's header). [heads] are the topology's launch
 *  specs: a head whose policy shares mcps is a home the materializer writes, so the servers in it are
 *  this daemon's copies and take the canonical plan's answer (v0.4.0 mcp review). */
internal class McpInventoryWiring(
    home: Path,
    sharing: McpSharing,
    global: GlobalMcpServers,
    heads: Collection<LaunchSpec>,
) {
    val inventory: McpInventory = McpInventory(
        readers = mapOf(
            McpSourceKind.GLOBAL to GlobalMcpServersReader(home),
            McpSourceKind.PROJECT to ProjectMcpServersReader(home),
            McpSourceKind.REPO to RepoMcpJsonReader(home),
            McpSourceKind.PLUGIN_MCP_JSON to PluginMcpJsonReader(home),
            McpSourceKind.PLUGIN_INLINE to PluginInlineReader(home),
        ),
        canonicalGlobalFile = home.resolve(".claude.json"),
        canonicalPlan = McpGlobalPlan { sharing.plan(global()) },
        materializedHomes = heads.filter { it.policy.sharesMcp() }.map { it.trees.own }.toSet(),
    )
}
