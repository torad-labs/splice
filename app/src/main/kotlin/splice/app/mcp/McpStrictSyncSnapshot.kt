// Single-read strict snapshot of Splice's global MCP sources (`mcp-registry.json`, `~/.claude.json`,
// and Temple registration) used by `McpHarnessSync` and `McpHarnessAutoSync` so `~/.claude.json` is
// read and parsed once per sync without a TOCTOU second read that could silently collapse to empty.
package splice.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerInput
import splice.client.mcp.TempleMcpEntry
import splice.core.config.StatePaths
import splice.core.mcp.McpHarness
import splice.core.mcp.McpUserRegistry
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** One strict, single-read snapshot of connected harnesses and merged global MCP inputs. */
internal data class McpSyncSnapshot(
    val harnesses: Set<McpHarness>,
    val inputs: List<McpServerInput>,
)

/** Reads `mcp-registry.json` and `~/.claude.json` strictly once each, failing closed on corruption or `EACCES`. */
internal class McpStrictSyncSnapshot(
    private val home: Path,
    private val paths: StatePaths,
    private val env: EnvReader = paths.envReader,
    private val registry: McpUserRegistry = McpUserRegistry(paths),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): McpSyncSnapshot {
        val regSnapshot = registry.readStrict()
        val claudeServers = readClaudeJsonStrict(home.resolve(".claude.json"))
        val templeInputs = TempleMcpEntry(home, env, paths).inputs()
        val imported = regSnapshot.entryMap().map { (name, entry) -> McpServerInput(name, entry) }
        val declared = claudeServers.map { (name, entry) -> McpServerInput(name, entry) }
        val combined = (imported + declared + templeInputs).associateBy(McpServerInput::name).values.toList()
        return McpSyncSnapshot(regSnapshot.harnesses, combined)
    }

    private fun readClaudeJsonStrict(file: Path): JsonObject {
        if (Files.notExists(file)) return JsonObject(emptyMap())
        val text = Files.readString(file)
        val parsed = JsonScalars.objectOrNull(json, text)
            ?: throw IOException("Malformed global MCP configuration: $file")
        val servers = parsed["mcpServers"]
        return when {
            servers == null -> JsonObject(emptyMap())
            servers is JsonObject -> servers
            else -> throw IOException("Malformed mcpServers map in $file")
        }
    }
}
