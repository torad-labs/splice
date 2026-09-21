// NEW: V4-146 (2026-09-20) — kinds GLOBAL and PROJECT: both live inside every home's own
// `.claude.json` (top-level `mcpServers`, and `projects.<path>.mcpServers`), so they share one
// file (concentration) — but each is its own McpSourceReader, per the fence's per-kind design.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.ClaudeHomes
import splice.client.Keys
import splice.core.util.Cancellables
import java.nio.file.Path

/** Kind GLOBAL: every home's own top-level `mcpServers` — widened from the one canonical home the
 *  materializer reads (see McpSources.kt's header). */
public class GlobalMcpServersReader(private val homeParent: Path) : McpSourceReader {
    private val homes = ClaudeHomes(homeParent)
    private val json = SourceJson()

    override fun invoke(): McpSourceScan {
        val files = homes.claudeJsonFiles()
        val regs = files.flatMap { file -> json.registrations(McpSourceKind.GLOBAL, file, null, json.mcpServers(file)) }
        return McpSourceScan(McpSourceKind.GLOBAL, homes.roots(), files, regs)
    }
}

/** Kind PROJECT: `projects.<path>.mcpServers` nested inside every home's `.claude.json`. */
public class ProjectMcpServersReader(private val homeParent: Path) : McpSourceReader {
    private val homes = ClaudeHomes(homeParent)
    private val json = SourceJson()

    override fun invoke(): McpSourceScan {
        val files = homes.claudeJsonFiles()
        val regs = files.flatMap { file -> projectRegistrations(file) }
        return McpSourceScan(McpSourceKind.PROJECT, homes.roots(), files, regs)
    }

    private fun projectRegistrations(file: Path): List<McpRegistration> {
        val projects = json.topLevel(file)[Keys.PROJECTS] as? JsonObject ?: return emptyList()
        return projects.entries.flatMap { (pathText, meta) ->
            val servers = (meta as? JsonObject)?.get(Keys.MCP_SERVERS) as? JsonObject ?: return@flatMap emptyList()
            val projectPath = Cancellables.runCatchingCancellable { Path.of(pathText) }.getOrNull()
            json.registrations(McpSourceKind.PROJECT, file, projectPath, servers)
        }
    }
}
