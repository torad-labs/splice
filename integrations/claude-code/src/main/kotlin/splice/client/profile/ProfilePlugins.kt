// NEW: terminal launch profiles (issue #791, comment §4). The plugins a profile loads, in the profile's own
// `plugins/`: its `installed_plugins.json` lists only the kept plugins the person enabled, each at its mirror in the
// profile's cache (ProfilePluginCache), so with keep_hooks "own" or "none" no plugin hook slips in. The marketplaces
// Claude Code knows are carried as they are, so it fetches none again. Nothing in the person's `~/.claude/plugins` is
// ever written.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.client.mcp.PLUGINS_DIR
import splice.core.util.SecureFile
import java.nio.file.Files
import java.nio.file.Path

internal class ProfilePlugins(
    private val claudeDir: Path,
    /** Whether a kept plugin's own hooks ride with it (keep_hooks "all"). */
    private val withHooks: Boolean,
    private val maxEntries: Int = PROFILE_MAX_ENTRIES,
) {
    private val json = Json { prettyPrint = true }

    /** [folder]'s `plugins/` brought to the plugins [kept], by id. */
    fun build(folder: Path, kept: Set<String>) {
        val plugins = folder.resolve(PLUGINS_DIR)
        if (Files.isSymbolicLink(plugins)) Files.delete(plugins)
        val staging = plugins.resolve(STAGING)
        ProfileTree(maxEntries).clear(staging)
        val mirrors = plugins.resolve(MIRRORS_JSON)
        val before = read(mirrors) ?: JsonObject(emptyMap())
        val cache = ProfilePluginCache(plugins.resolve(PLUGIN_CACHE), staging, before, withHooks, maxEntries)
        val yours = claudeDir.resolve(PLUGINS_DIR)
        val installed = read(yours.resolve(INSTALLED_PLUGINS_JSON))
        val records = (installed?.get(PLUGIN_RECORDS) as? JsonObject).orEmpty()
            .filterKeys { it in kept }
            .mapValues { (id, list) -> JsonArray((list as? JsonArray).orEmpty().mapNotNull { cache.at(id, it) }) }
            .filterValues { it.isNotEmpty() }
        cache.dropUnkept()
        write(mirrors, JsonObject(cache.placed))
        val listed = installed?.let { JsonObject(it + (PLUGIN_RECORDS to JsonObject(records))) }
        carry(plugins.resolve(INSTALLED_PLUGINS_JSON), listed)
        carry(plugins.resolve(KNOWN_MARKETPLACES_JSON), read(yours.resolve(KNOWN_MARKETPLACES_JSON)))
    }

    private fun read(file: Path): JsonObject? = (DesktopSettingsRead(file).read() as? SettingsRead.Held)?.file

    /** [file] holding [body], or taken away while the person has no such file, so Claude Code starts its own. */
    private fun carry(file: Path, body: JsonObject?) {
        if (body == null) Files.deleteIfExists(file) else write(file, body)
    }

    private fun write(file: Path, body: JsonObject) {
        SecureFile.writeAtomic0600(file, json.encodeToString(JsonElement.serializer(), body))
    }
}

// Where Claude Code keeps installed plugins under its config folder, and the record of each install.
private const val PLUGIN_CACHE = "cache"
private const val INSTALLED_PLUGINS_JSON = "installed_plugins.json"
private const val KNOWN_MARKETPLACES_JSON = "known_marketplaces.json"
private const val PLUGIN_RECORDS = "plugins"

// splice's own beside them: where a mirror is built before it takes its place, and what each mirror was built from.
private const val STAGING = ".splice-staging"
private const val MIRRORS_JSON = ".splice-mirrors.json"
