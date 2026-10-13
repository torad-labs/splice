// NEW: MCP hub step 1 (docs/specs/mcp-hub.md, draft 2, approved by Marcos on Oct 12) — the servers Claude Code
// itself would load when it starts in one folder under one config home, read the way Claude Code 2.1.296 reads
// them. Read-only: nothing here writes, and no launch changes until step 6.
//
// THE SOURCES, from the client's own loaders (bundle fn W8t and Xi): user scope is the top-level `mcpServers` of
// the config home's `.claude.json`; local scope is the `mcpServers` of that file's record for the project, keyed
// by the folder's git root (else the folder); project scope is every `.mcp.json` from the folder up to the
// filesystem root, the nearer file winning a name, each kept only when approved (McpRepoApproval). For one name
// local beats project beats user, and the whole entry of the winner is used. Then the enabled plugins' servers
// (McpPluginServers), each dropped when it runs the same command or URL as a server above, which Claude Code
// treats as a duplicate. A server the project's `disabledMcpServers` names is off in that folder.
//
// TRUST. The folder's checked-in and local settings count only once the folder is trusted: a `projects` record
// with hasTrustDialogAccepted for the folder or a parent within its git root, or the folder is home, Claude
// Code's own config home. The user's settings and managed settings always count.
package splice.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import splice.client.Keys
import splice.client.RealPathOrSelf
import java.nio.file.Files
import java.nio.file.Path

private const val PLAN_LOCAL_SETTINGS = "settings.local.json"
private const val PLAN_MANAGED_SETTINGS = "/etc/claude-code/managed-settings.json"
private const val PLAN_ENABLED_PLUGINS = "enabledPlugins"
private const val PLAN_FOLDER_DISABLED = "disabledMcpServers"
private const val PLAN_FOLDER_TRUSTED = "hasTrustDialogAccepted"
private const val PLAN_ENTRY_URL = "url"
private const val PLAN_ENTRY_COMMAND = "command"
private const val PLAN_ENTRY_ARGS = "args"
private const val TURNED_OFF_HERE = "turned off for this folder in /mcp"

/** [home] is the user's home folder: the vanilla config home `~/.claude` keeps its `.claude.json` there. */
public class McpLaunchPlanner(
    private val home: Path,
    private val managedSettings: Path = Path.of(PLAN_MANAGED_SETTINGS),
) {
    private val json = SourceJson()
    private val realPath = RealPathOrSelf()
    private val entries = PlanEntryText()

    /** The plan for Claude Code started in [folder] with [configDir] as its config home: its CLAUDE_CONFIG_DIR, or
     *  `~/.claude` when that is unset. */
    public fun plan(configDir: Path, folder: Path): McpLaunchPlan {
        val stateFile = stateFile(configDir)
        val state = json.topLevel(stateFile)
        val here = realPath(folder)
        val project = projectRoot(here)
        val record = record(state, project)
        val settings = settings(configDir, here, trusted(state, here, project))
        val approval = McpRepoApproval(settings + record)
        val (approved, waiting) = repoServers(here).partition { approval.verdict(it.name) == RepoVerdict.APPROVED }
        val user = servers(McpPlanScope.USER, stateFile, state)
        val scoped = (user + approved + servers(McpPlanScope.LOCAL, stateFile, record)).associateBy { it.name }
        val plugins = McpPluginServers(configDir.resolve(PLUGINS_DIR), json)
            .servers(enabledPlugins(settings), project, here)
        val (copies, own) = plugins.partition { plugin -> original(scoped.values, plugin) != null }
        val off = entries.names(record, PLAN_FOLDER_DISABLED)
        val (shut, loaded) = (scoped.values + own).sortedBy { it.name }.partition { it.name in off }
        val unapproved = waiting.filterNot { it.name in scoped }
        return McpLaunchPlan(
            folder = here,
            configDir = configDir,
            servers = loaded,
            leftOut = shut.map { left(it, TURNED_OFF_HERE) } +
                unapproved.map { left(it, approval.verdict(it.name).reason.orEmpty()) } +
                copies.map { left(it, "runs the same server as ${original(scoped.values, it)?.name}, loaded instead") },
        )
    }

    /** The vanilla config home keeps its `.claude.json` in home, beside `.claude`; every other keeps it inside. */
    private fun stateFile(configDir: Path): Path =
        (if (configDir == home.resolve(Keys.VANILLA_DIR)) home else configDir).resolve(Keys.CLAUDE_JSON)

    private fun left(server: McpPlannedServer, reason: String) =
        McpLeftOutServer(server.name, server.scope, server.source, reason)

    /** The nearest folder at or above [folder] holding `.git`, else [folder]: the key of its `projects` record. */
    private fun projectRoot(folder: Path): Path =
        generateSequence(folder) { it.parent }.firstOrNull { Files.exists(it.resolve(GIT_DIR)) } ?: folder

    private fun record(state: JsonObject, project: Path): JsonObject =
        (state[Keys.PROJECTS] as? JsonObject)?.get(project.toString()) as? JsonObject ?: JsonObject(emptyMap())

    /** Home, or a trusted record for the folder or a parent up to its git root. */
    private fun trusted(state: JsonObject, folder: Path, project: Path): Boolean {
        if (folder == realPath(home)) return true
        val projects = state[Keys.PROJECTS] as? JsonObject ?: return false
        val lineage = generateSequence(folder) { it.parent?.takeIf { _ -> it != project } }
        return lineage.any { dir ->
            val entry = projects[dir.toString()] as? JsonObject
            (entry?.get(PLAN_FOLDER_TRUSTED) as? JsonPrimitive)?.booleanOrNull == true
        }
    }

    /** The settings Claude Code reads for the folder, lowest precedence first: the user's, the folder's checked-in and
     *  local ones once it is trusted, then managed settings. */
    private fun settings(configDir: Path, folder: Path, trusted: Boolean): List<JsonObject> {
        val own = folder.resolve(Keys.VANILLA_DIR)
        val repo = if (trusted) listOf(own.resolve(Keys.SETTINGS), own.resolve(PLAN_LOCAL_SETTINGS)) else emptyList()
        return (listOf(configDir.resolve(Keys.SETTINGS)) + repo + managedSettings).map(json::topLevel)
    }

    /** Every `.mcp.json` from the filesystem root down to [folder], the nearer file winning a name. */
    private fun repoServers(folder: Path): List<McpPlannedServer> =
        generateSequence(folder) { it.parent }.takeWhile { it.parent != null }.toList().asReversed()
            .flatMap { dir -> dir.resolve(MCP_JSON_FILE).let { servers(McpPlanScope.PROJECT, it, json.topLevel(it)) } }
            .associateBy { it.name }.values.toList()

    private fun servers(scope: McpPlanScope, source: Path, holder: JsonObject): List<McpPlannedServer> =
        (holder[Keys.MCP_SERVERS] as? JsonObject)?.mapNotNull { (name, entry) ->
            (entry as? JsonObject)?.let { McpPlannedServer(name, scope, source, it) }
        }.orEmpty()

    /** `enabledPlugins` merged across [settings], a later source winning a plugin. */
    private fun enabledPlugins(settings: List<JsonObject>): Map<String, Boolean> =
        settings.fold(emptyMap<String, Boolean>()) { merged, source ->
            val here = (source[PLAN_ENABLED_PLUGINS] as? JsonObject).orEmpty()
            merged + here.mapNotNull { (id, on) -> (on as? JsonPrimitive)?.booleanOrNull?.let { id to it } }
        }

    /** The server among [loaded] that runs what [plugin] runs. */
    private fun original(loaded: Collection<McpPlannedServer>, plugin: McpPlannedServer): McpPlannedServer? =
        entries.endpoint(plugin.entry)?.let { runs -> loaded.firstOrNull { entries.endpoint(it.entry) == runs } }
}

/** The text an entry or a record holds, read the one way the plan reads it. */
internal class PlanEntryText {
    /** What a server runs: its URL, or its command with its arguments. */
    fun endpoint(entry: JsonObject): List<String>? = text(entry[PLAN_ENTRY_URL])?.let { listOf(it) }
        ?: text(entry[PLAN_ENTRY_COMMAND])?.let { command -> listOf(command) + strings(entry[PLAN_ENTRY_ARGS]) }

    fun names(holder: JsonObject, key: String): Set<String> = strings(holder[key]).toSet()

    private fun strings(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { text(it) }.orEmpty()

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
