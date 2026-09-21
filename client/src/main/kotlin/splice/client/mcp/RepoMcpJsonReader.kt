// NEW: V4-146 (2026-09-20) — kind REPO: a repo's own `.mcp.json`. Split out of the other readers
// (concentration) because its denominator (project paths) is derived, not read from one file.
package splice.client.mcp

import kotlinx.serialization.json.JsonObject
import splice.client.ClaudeHomes
import splice.client.Keys
import splice.client.RealPathOrSelf
import splice.core.util.Cancellables
import java.nio.file.Path

/** Kind REPO: a repo's own `.mcp.json`. The denominator is every project path any home's
 *  `.claude.json` already knows about (Claude Code's own project history) — a filesystem-derived
 *  list, never a hand-authored one — deduplicated by real path so a project opened under several
 *  Claude identities is checked once. */
public class RepoMcpJsonReader(private val homeParent: Path) : McpSourceReader {
    private val homes = ClaudeHomes(homeParent)
    private val json = SourceJson()
    private val realPath = RealPathOrSelf()

    override fun invoke(): McpSourceScan {
        val projectRoots = homes.claudeJsonFiles().flatMap { projectPaths(it) }
            .distinctBy { realPath(it) }
            .sortedBy { it.toString() }
        val candidates = projectRoots.map { it.resolve(MCP_JSON_FILE) }
        val regs = candidates.flatMap { file ->
            json.registrations(McpSourceKind.REPO, file, file.parent, json.mcpServers(file))
        }
        return McpSourceScan(McpSourceKind.REPO, projectRoots, candidates, regs)
    }

    private fun projectPaths(file: Path): List<Path> {
        val projects = json.topLevel(file)[Keys.PROJECTS] as? JsonObject ?: return emptyList()
        return projects.keys.mapNotNull { Cancellables.runCatchingCancellable { Path.of(it) }.getOrNull() }
    }
}
