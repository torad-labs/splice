// NEW: terminal launch profiles (issue #791, comment §4). The plugins a profile loads, in the profile's own
// `plugins/`: its `installed_plugins.json` lists only the kept plugins the person enabled, each installed at
// `plugins/cache/<marketplace>/<name>/<version>/` in the profile, a folder of links to the plugin as the person
// installed it. With keep_hooks "own" or "none" that folder leaves the plugin's hooks out (its `hooks/` folder and the
// `hooks` its manifest declares), so no plugin hook slips in; with "all" it carries them. The marketplaces Claude Code
// knows are carried as they are, so it fetches none again. The cache is rebuilt whole at each launch; nothing in the
// person's `~/.claude/plugins` is ever written.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.Keys
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.client.mcp.CLAUDE_PLUGIN_DIR
import splice.client.mcp.PLUGINS_DIR
import splice.client.mcp.PLUGIN_JSON_FILE
import splice.core.util.JsonScalars
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

internal class ProfilePlugins(
    private val claudeDir: Path,
    /** Whether a kept plugin's own hooks ride with it (keep_hooks "all"). */
    private val withHooks: Boolean,
    private val maxEntries: Int = PROFILE_MAX_ENTRIES,
) {
    private val json = Json { prettyPrint = true }

    /** [folder]'s `plugins/` rebuilt for the plugins [kept], by id. */
    fun build(folder: Path, kept: Set<String>) {
        val plugins = folder.resolve(PLUGINS_DIR)
        if (Files.isSymbolicLink(plugins)) Files.delete(plugins)
        val cache = plugins.resolve(PLUGIN_CACHE)
        ProfileTree(maxEntries).clear(cache)
        val yours = claudeDir.resolve(PLUGINS_DIR)
        val installed = read(yours.resolve(INSTALLED_PLUGINS_JSON))
        val records = (installed?.get(PLUGIN_RECORDS) as? JsonObject).orEmpty()
            .filterKeys { it in kept }
            .mapValues { (id, list) -> JsonArray((list as? JsonArray).orEmpty().map { installAt(cache, id, it) }) }
        val listed = installed?.let { JsonObject(it + (PLUGIN_RECORDS to JsonObject(records))) }
        carry(plugins.resolve(INSTALLED_PLUGINS_JSON), listed)
        carry(plugins.resolve(KNOWN_MARKETPLACES_JSON), read(yours.resolve(KNOWN_MARKETPLACES_JSON)))
    }

    /** One install record of plugin [id], pointed at its copy in the profile's [cache]. */
    private fun installAt(cache: Path, id: String, record: JsonElement): JsonElement {
        val held = record as? JsonObject
        val source = JsonScalars.str(held, INSTALL_PATH)?.let { Paths.get(it) }?.takeIf { Files.isDirectory(it) }
        if (held == null || source == null) return record
        val version = JsonScalars.str(held, INSTALL_VERSION) ?: source.fileName.toString()
        val target = cache.resolve(id.substringAfter(PLUGIN_AT, NO_MARKETPLACE)).resolve(id.substringBefore(PLUGIN_AT))
            .resolve(version).normalize()
        if (!target.startsWith(cache)) return record
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) mirror(source, target)
        return JsonObject(held + (INSTALL_PATH to JsonPrimitive(target.toString())))
    }

    /** [target], a folder of links to [source]'s entries, without the plugin's hooks unless they ride. */
    private fun mirror(source: Path, target: Path) {
        Files.createDirectories(target)
        entries(source).forEach { entry ->
            val name = entry.fileName.toString()
            when {
                name == Keys.HOOKS && !withHooks -> Unit
                name == CLAUDE_PLUGIN_DIR && !withHooks && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) ->
                    manifest(entry, target.resolve(name))
                else -> Files.createSymbolicLink(target.resolve(name), entry)
            }
        }
    }

    /** The plugin's manifest folder with its manifest's `hooks` taken out and every other entry linked. */
    private fun manifest(source: Path, target: Path) {
        Files.createDirectories(target)
        entries(source).forEach { entry ->
            val name = entry.fileName.toString()
            if (name != PLUGIN_JSON_FILE) {
                Files.createSymbolicLink(target.resolve(name), entry)
            } else {
                read(entry)?.let { declared -> write(target.resolve(name), JsonObject(declared - Keys.HOOKS)) }
            }
        }
    }

    private fun entries(dir: Path): List<Path> =
        Files.list(dir).use { stream -> stream.limit(maxEntries.toLong()).sorted().toList() }

    private fun read(file: Path): JsonObject? = (DesktopSettingsRead(file).read() as? SettingsRead.Held)?.file

    /** [file] holding [body], or taken away while the person has no such file, so Claude Code starts its own. */
    private fun carry(file: Path, body: JsonObject?) {
        if (body == null) Files.deleteIfExists(file) else write(file, body)
    }

    private fun write(file: Path, body: JsonObject) {
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonElement.serializer(), body))
    }
}

// Where Claude Code keeps installed plugins under its config folder, the record of each install and where it lives.
private const val PLUGIN_CACHE = "cache"
private const val INSTALLED_PLUGINS_JSON = "installed_plugins.json"
private const val KNOWN_MARKETPLACES_JSON = "known_marketplaces.json"
private const val PLUGIN_RECORDS = "plugins"
private const val INSTALL_PATH = "installPath"
private const val INSTALL_VERSION = "version"

// The marketplace part of an id that names none.
private const val NO_MARKETPLACE = "unknown"
