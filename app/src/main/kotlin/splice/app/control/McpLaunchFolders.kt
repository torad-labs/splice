// NEW: MCP hub step 1 (docs/specs/mcp-hub.md, draft 2, approved Oct 12) — the plans /api/mcp reports: one per
// config home and folder a running Claude Code session started in, read from the session registry and each
// session's own environment, so a plain `claude` and every head's command are planned under the home they really
// read. Read-only, and computed per request, so an edit to his files shows on the next read.
package splice.app.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpLaunchPlanner
import splice.core.config.UserHome
import splice.core.util.PathProbe
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import java.nio.file.Path

/** One launch the plan covers: the config home a session runs under and the folder it started in. */
internal data class PlannedLaunch(val configDir: Path, val folder: Path)

internal class McpLaunchFolders(
    private val sessions: SessionSource?,
    private val planner: McpLaunchPlanner = McpLaunchPlanner(UserHome.dir()),
    private val environment: ProcessEnvironment = ProcessEnvironment(),
) {
    /** Every launch's plan with the number of sessions it covers, ordered by folder then config home. */
    fun json(): JsonArray {
        val vanilla = UserHome.claudeDir()
        val launches = sessions?.read().orEmpty()
            .filter { it.availability != SessionAvailability.GONE }
            .mapNotNull { record ->
                val folder = record.process.cwd?.let(PathProbe::spelled)?.takeIf { it.isAbsolute }
                val home = record.process.pid?.let { environment.configHome(it, vanilla) }
                folder?.let { home?.let { PlannedLaunch(it, folder) } }
            }
        val counted = launches.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key.folder.toString() }, { it.key.configDir.toString() }))
        return JsonArray(
            counted.map { (launch, count) ->
                val plan = planner.plan(launch.configDir, launch.folder).json()
                JsonObject(plan + ("sessions" to JsonPrimitive(count)))
            },
        )
    }
}
