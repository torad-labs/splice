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
import splice.client.mcp.McpPlanScope
import splice.core.config.UserHome
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionSource
import java.nio.file.Path

/** One launch the plan covers: the config home a session runs under and the folder it started in. */
internal data class PlannedLaunch(val configDir: Path, val folder: Path)

// why: the key both the census and each plan list their servers under on /api/mcp.
private const val SERVERS_KEY = "servers"

/** One launch's plan and how many running sessions started there, read once per /api/mcp answer. */
internal data class CountedPlan(val plan: McpLaunchPlan, val sessions: Int)

/** A census row as the plan names it: the same name, scope and file. A plugin's server carries the plugin in the
 *  plan's name (`plugin_<plugin>_<name>`, the name its tools take) and only its own name in the census. */
private data class Declared(val name: String, val scope: McpPlanScope, val file: Path) {
    fun matches(planned: String, plannedScope: McpPlanScope, source: Path): Boolean {
        val named = if (scope == McpPlanScope.PLUGIN) planned.endsWith("_$name") else planned == name
        return named && plannedScope == scope && source.normalize() == file
    }
}

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
    /** The plan scope each census row kind is declared under: Claude Code calls a project record's servers local and
     *  a repo .mcp.json's project. */
    private val plannedAs = mapOf(
        "global" to McpPlanScope.USER,
        "project" to McpPlanScope.LOCAL,
        "repo" to McpPlanScope.PROJECT,
        "plugin_mcp_json" to McpPlanScope.PLUGIN,
        "plugin_inline" to McpPlanScope.PLUGIN,
    )

    /** Every launch's plan, as /api/mcp's `plans` reports it. */
    fun json(counted: List<CountedPlan>): JsonArray =
        JsonArray(counted.map { (plan, count) -> JsonObject(classed(plan) + ("sessions" to JsonPrimitive(count))) })

    /**
     * The census [sources] with each server's place in the plans (MCP hub step 1: the plan is reported in the census).
     * Each row gains `launches`: every planned launch that loads it, and every one that leaves it out with the
     * planner's reason (awaiting approval, rejected, plugin off, replaced by a nearer scope). Its other fields keep
     * their values. An empty list means no running session started where the server would load.
     */
    fun placed(sources: JsonObject?, read: List<CountedPlan>): JsonObject? {
        val rows = sources?.get(SERVERS_KEY) as? JsonArray ?: return sources
        val placed = rows.map { row ->
            (row as? JsonObject)?.let { JsonObject(it + ("launches" to launches(it, read))) } ?: row
        }
        return JsonObject(sources + (SERVERS_KEY to JsonArray(placed)))
    }

    private fun launches(row: JsonObject, read: List<CountedPlan>): JsonArray {
        val scope = plannedAs[JsonScalars.str(row, "kind")]
        val file = JsonScalars.str(row, "source_file")?.let { Path.of(it).normalize() }
        if (scope == null || file == null) return JsonArray(emptyList())
        val declared = Declared(JsonScalars.str(row, "name").orEmpty(), scope, file)
        // a project record names the folder its servers load under: a launch outside it never reads them
        val project = JsonScalars.str(row, "scope")?.takeIf { scope == McpPlanScope.LOCAL }
            ?.let { Path.of(it).normalize() }
        val considered = read.map { it.plan }.filter { project == null || it.folder.normalize().startsWith(project) }
        return JsonArray(
            considered.mapNotNull { plan ->
                val left = plan.leftOut.firstOrNull { declared.matches(it.name, it.scope, it.source) }
                when {
                    plan.servers.any { declared.matches(it.name, it.scope, it.source) } -> place(plan, null)
                    left != null -> place(plan, left.reason)
                    else -> null
                }
            },
        )
    }

    /** One launch's answer for a server: loaded, or left out with [reason]. */
    private fun place(plan: McpLaunchPlan, reason: String?): JsonObject = JsonObject(
        listOfNotNull(
            "folder" to JsonPrimitive(plan.folder.toString()),
            "config_dir" to JsonPrimitive(plan.configDir.toString()),
            "loaded" to JsonPrimitive(reason == null),
            reason?.let { "reason" to JsonPrimitive(it) },
        ).toMap(),
    )

    /** Every launch with the number of sessions it covers, ordered by folder then config home. */
    fun read(): List<CountedPlan> {
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
        return counted.map { (launch, count) -> CountedPlan(planner.plan(launch.configDir, launch.folder), count) }
    }

    /** [plan]'s JSON with each server's class beside its scope, and the reason a server is not shared as the same
     *  `why` and `value` /api/mcp carries: Desktop Commander as a plugin and Remotion as a project server are
     *  classed as a user-scope one would be (MCP hub step 3). */
    private fun classed(plan: McpLaunchPlan): JsonObject {
        val json = plan.json()
        val decided = classes?.plan(JsonObject(plan.servers.associate { it.name to it.entry })) ?: return json
        val servers = (json[SERVERS_KEY] as? JsonArray).orEmpty().map { server ->
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
        return JsonObject(json + (SERVERS_KEY to JsonArray(servers)))
    }
}
