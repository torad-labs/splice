// NEW: V4-146 (2026-09-20) — kinds PLUGIN_MCP_JSON and PLUGIN_INLINE: both walk the same
// `plugins` trees (PluginRoots, ClaudeHomes.kt) for a different manifest shape, so they share one
// file (concentration) — but each is its own McpSourceReader.
package splice.client.mcp

import splice.client.PluginFileWalk
import splice.client.PluginRoots
import java.nio.file.Path
import kotlin.io.path.name

/** Kind PLUGIN_MCP_JSON: a plugin's own `.mcp.json`, wherever it sits under a `plugins` tree —
 *  read-only, plugin-owned (McpInventory's disposition, not this reader's concern). */
public class PluginMcpJsonReader(private val homeParent: Path) : McpSourceReader {
    private val pluginRoots = PluginRoots(homeParent)
    private val walk = PluginFileWalk()
    private val json = SourceJson()

    override fun invoke(): McpSourceScan {
        val roots = pluginRoots.roots()
        val files = roots.flatMap { root -> walk(root) { it.name == MCP_JSON_FILE } }
        val regs = files.flatMap { file ->
            json.registrations(McpSourceKind.PLUGIN_MCP_JSON, file, file.parent, json.mcpServers(file))
        }
        return McpSourceScan(McpSourceKind.PLUGIN_MCP_JSON, roots, files, regs)
    }
}

/** Kind PLUGIN_INLINE: `mcpServers` declared inline inside a plugin's OWN `.claude-plugin/
 *  plugin.json` — the sibling manifest dirs other agents install (`.codex-plugin`,
 *  `.cursor-plugin`, …) are a different tool's contract and are not matched. */
public class PluginInlineReader(private val homeParent: Path) : McpSourceReader {
    private val pluginRoots = PluginRoots(homeParent)
    private val walk = PluginFileWalk()
    private val json = SourceJson()

    override fun invoke(): McpSourceScan {
        val roots = pluginRoots.roots()
        val files = roots.flatMap { root ->
            walk(root) { it.name == PLUGIN_JSON_FILE && it.parent?.name == CLAUDE_PLUGIN_DIR }
        }
        val regs = files.flatMap { file ->
            json.registrations(McpSourceKind.PLUGIN_INLINE, file, file.parent?.parent, json.mcpServers(file))
        }
        return McpSourceScan(McpSourceKind.PLUGIN_INLINE, roots, files, regs)
    }
}
