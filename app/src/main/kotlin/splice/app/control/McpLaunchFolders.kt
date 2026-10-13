// NEW: MCP hub step 1 (docs/specs/mcp-hub.md, draft 2, approved Oct 12) — the plans /api/mcp reports: one per
// config home and folder a running Claude Code session started in, read from the session registry and each
// session's own environment, so a plain `claude` and every head's command are planned under the home they really
// read. Read-only, and computed per request, so an edit to his files shows on the next read.
package splice.app.control

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.mcp.McpLaunchPlan
import splice.client.mcp.McpLaunchPlanner
import splice.client.mcp.McpPlan
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import java.nio.file.Path

/** One launch the plan covers: the config home a session runs under and the folder it started in. */
internal data class PlannedLaunch(val configDir: Path, val folder: Path)

/** How the hub would run each of one launch's servers (MCP hub step 3); the host's plan in production. */
internal fun interface McpServerClasses {
    fun plan(servers: JsonObject): McpPlan
}

internal class McpLaunchFolders(
    private val sessions: SessionSource?,
    /** Classes every planned server, whatever its source, beside its scope; null leaves the plans unclassed. */
    private val classes: McpServerClasses? = null,
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
                val plan = classed(planner.plan(launch.configDir, launch.folder))
                JsonObject(plan + ("sessions" to JsonPrimitive(count)))
            },
        )
    }

    /** [plan]'s JSON with each server's class beside its scope, and the reason a server is not shared as the same
     *  `why` and `value` /api/mcp carries: Desktop Commander as a plugin and Remotion as a project server are
     *  classed as a user-scope one would be (MCP hub step 3). */
    private fun classed(plan: McpLaunchPlan): JsonObject {
        val json = plan.json()
        val decided = classes?.plan(JsonObject(plan.servers.associate { it.name to it.entry })) ?: return json
        val servers = (json["servers"] as? JsonArray).orEmpty().map { server ->
            val row = server as? JsonObject ?: return@map server
            val name = JsonScalars.str(row, "name").orEmpty()
            val why = decided.why[name]
            val fields = listOfNotNull(
                "class" to JsonPrimitive(decided.classOf(name).wire),
                why?.let { "why" to JsonPrimitive(it.key) },
                why?.value?.let { "value" to JsonPrimitive(it) },
            )
            JsonObject(row + fields)
        }
        return JsonObject(json + ("servers" to JsonArray(servers)))
    }
}
