// NEW: MCP hub step 1 (docs/specs/mcp-hub.md) — the servers of the plugins enabled for one launch. A plugin is on
// when the merged `enabledPlugins` says true for its id (`<plugin>@<marketplace>`). Its files are the install the
// config home's `plugins/installed_plugins.json` records for it: the local or project install made for this
// project, else the user install. Its servers are its root's `.mcp.json` and the `mcpServers` its
// `.claude-plugin/plugin.json` declares (an object, a file beside it, or a list of either), named the way Claude
// Code names them, `plugin_<plugin>_<server>` (proof a), with `${CLAUDE_PLUGIN_ROOT}` filled in as the install path.
package splice.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import splice.client.Keys
import java.nio.file.Path

private const val INSTALLED_PLUGINS_FILE = "installed_plugins.json"
private const val INSTALLED_PLUGINS_KEY = "plugins"
private const val INSTALL_PATH_KEY = "installPath"
private const val INSTALL_SCOPE_KEY = "scope"
private const val INSTALL_PROJECT_KEY = "projectPath"
private const val USER_INSTALL_SCOPE = "user"
private const val PLUGIN_ROOT_VARIABLE = "\${CLAUDE_PLUGIN_ROOT}"
private const val PLUGIN_SERVER_PREFIX = "plugin_"
private const val PLUGIN_ID_SEPARATOR = '@'

/** [pluginsDir] is the config home's `plugins` folder. */
internal class McpPluginServers(private val pluginsDir: Path, private val json: SourceJson) {
    /** Every server of every plugin [enabled] turns on, for a launch whose project is [project]. */
    fun servers(enabled: Map<String, Boolean>, project: Path): List<McpPlannedServer> {
        val installs = json.topLevel(pluginsDir.resolve(INSTALLED_PLUGINS_FILE))[INSTALLED_PLUGINS_KEY] as? JsonObject
        return enabled.filterValues { it }.keys.sorted().flatMap { id ->
            val root = (installs?.get(id) as? JsonArray)?.let { install(it, project) }
            root?.let { pluginServers(id.substringBefore(PLUGIN_ID_SEPARATOR), it) }.orEmpty()
        }
    }

    /** The install made for [project], else the user install, else none. */
    private fun install(records: JsonArray, project: Path): Path? {
        val objects = records.filterIsInstance<JsonObject>()
        val own = objects.firstOrNull { text(it, INSTALL_SCOPE_KEY) != USER_INSTALL_SCOPE && projectOf(it) == project }
        val chosen = own ?: objects.firstOrNull { text(it, INSTALL_SCOPE_KEY) == USER_INSTALL_SCOPE }
        return chosen?.let { text(it, INSTALL_PATH_KEY) }?.let { Path.of(it) }
    }

    private fun projectOf(record: JsonObject): Path? = text(record, INSTALL_PROJECT_KEY)?.let { Path.of(it) }

    private fun pluginServers(plugin: String, root: Path): List<McpPlannedServer> {
        val mcpJson = root.resolve(MCP_JSON_FILE)
        val manifest = root.resolve(CLAUDE_PLUGIN_DIR).resolve(PLUGIN_JSON_FILE)
        val declared = json.mcpServers(mcpJson).map { (name, entry) -> PluginDeclared(name, entry, mcpJson) } +
            manifestServers(json.topLevel(manifest)[Keys.MCP_SERVERS], root, manifest)
        return declared.mapNotNull { (name, entry, source) ->
            (expanded(entry, root.toString()) as? JsonObject)?.let {
                McpPlannedServer("$PLUGIN_SERVER_PREFIX${plugin}_$name", McpPlanScope.PLUGIN, source, it)
            }
        }
    }

    /** A manifest's `mcpServers`: servers inline, a file of them relative to the plugin root, or a list of either. */
    private fun manifestServers(found: JsonElement?, root: Path, manifest: Path): List<PluginDeclared> = when (found) {
        is JsonObject -> found.map { (name, entry) -> PluginDeclared(name, entry, manifest) }
        is JsonPrimitive -> found.contentOrNull?.let { root.resolve(it).normalize() }
            ?.let { file -> json.mcpServers(file).map { (name, entry) -> PluginDeclared(name, entry, file) } }
            .orEmpty()
        is JsonArray -> found.flatMap { manifestServers(it, root, manifest) }
        else -> emptyList()
    }

    /** [element] with every `${CLAUDE_PLUGIN_ROOT}` in its strings replaced by [root]. */
    private fun expanded(element: JsonElement, root: String): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> expanded(value, root) })
        is JsonArray -> JsonArray(element.map { expanded(it, root) })
        is JsonPrimitive -> element.takeUnless { it.isString }
            ?: JsonPrimitive(element.content.replace(PLUGIN_ROOT_VARIABLE, root))
    }

    private fun text(record: JsonObject, key: String): String? = (record[key] as? JsonPrimitive)?.contentOrNull
}

/** One server a plugin declares, and the file it was declared in. */
internal data class PluginDeclared(val name: String, val entry: JsonElement, val source: Path)
