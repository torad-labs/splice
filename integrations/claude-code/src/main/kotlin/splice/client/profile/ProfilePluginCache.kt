// NEW: terminal launch profiles (issue #791, comment §4). A profile's own plugin cache: each kept plugin installed at
// `plugins/cache/<marketplace>/<name>/<version>/`, a folder of links to the plugin as the person installed it, without
// its hooks unless they ride (its `hooks/` folder, and the `hooks` its manifest declares). A session running in the
// profile reads its plugins from these paths, so a rebuild leaves every mirror that is still right exactly where it is:
// one is rebuilt only when what it mirrors changed, in a staging folder beside the cache, and moved into place whole.
// What each mirror was built from is kept as a digest, by path, in the profile's `plugins/.splice-mirrors.json`.
package splice.client.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.client.Keys
import splice.client.desktop.DesktopSettingsRead
import splice.client.desktop.SettingsRead
import splice.client.mcp.CLAUDE_PLUGIN_DIR
import splice.client.mcp.PLUGIN_JSON_FILE
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SecureFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

internal class ProfilePluginCache(
    private val cache: Path,
    /** A folder beside [cache] where a mirror is built before it takes its place, and an old one waits to go. */
    private val staging: Path,
    /** What each mirror was built from at the last launch: its path to the digest of its source. */
    private val before: JsonObject,
    /** Whether a kept plugin's own hooks ride with it (keep_hooks "all"). */
    private val withHooks: Boolean,
    private val maxEntries: Int = PROFILE_MAX_ENTRIES,
) {
    private val json = Json { prettyPrint = true }

    /** Every mirror this launch keeps, its path to the digest of what it mirrors. */
    val placed: MutableMap<String, JsonElement> = mutableMapOf()

    /** One install record of plugin [id] pointed at its mirror, made or kept; null for a record with no install here or
     *  one whose id or version would place it outside the cache, which the profile then does not list at all. */
    fun at(id: String, record: JsonElement): JsonElement? {
        val held = record as? JsonObject
        val source = JsonScalars.str(held, INSTALL_PATH)?.let { Paths.get(it) }?.takeIf { Files.isDirectory(it) }
        if (held == null || source == null) return null
        val version = JsonScalars.str(held, INSTALL_VERSION) ?: source.fileName.toString()
        val target = cache.resolve(id.substringAfter(PLUGIN_AT, NO_MARKETPLACE)).resolve(id.substringBefore(PLUGIN_AT))
            .resolve(version).normalize().takeIf { it.startsWith(cache) && it != cache } ?: return null
        keep(source, target)
        return JsonObject(held + (INSTALL_PATH to JsonPrimitive(target.toString())))
    }

    /** The mirrors an earlier launch made that this one no longer keeps, taken away. */
    fun dropUnkept() {
        val gone = before.keys.filter { it !in placed }.map { Paths.get(it) }
        gone.filter { it.startsWith(cache) && it != cache && inside(it) }.forEach(ProfileTree(maxEntries)::clear)
    }

    /** [target] a mirror of [source]: left as it is while it mirrors the same thing, rebuilt and swapped in
     *  otherwise. */
    private fun keep(source: Path, target: Path) {
        val key = target.toString()
        val digest = digest(source)
        val current = JsonScalars.str(before[key]) == digest && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
        if (key !in placed && !current) place(source, target)
        placed[key] = JsonPrimitive(digest)
    }

    /** [target] rebuilt from [source] aside, then moved into place whole; the one it replaces taken away after. */
    private fun place(source: Path, target: Path) {
        within(staging)
        val built = Files.createTempDirectory(staging, MIRROR_PREFIX)
        fill(source, built)
        within(target.parent)
        val old = staging.resolve("${built.fileName}$OLD_SUFFIX")
            .takeIf { Files.exists(target, LinkOption.NOFOLLOW_LINKS) }
            ?.also { Files.move(target, it, StandardCopyOption.ATOMIC_MOVE) }
        Files.move(built, target, StandardCopyOption.ATOMIC_MOVE)
        old?.let(ProfileTree(maxEntries)::clear)
    }

    /** [dir] made a real folder in the profile's plugins folder: a link on the way, which could lead out of it, is
     *  taken away as a link, and a folder that still resolves outside is refused before anything is written there. */
    private fun within(dir: Path) {
        val root = plugins()
        var at = root
        root.relativize(dir).forEach { part ->
            at = at.resolve(part)
            if (Files.isSymbolicLink(at)) Files.delete(at)
        }
        Files.createDirectories(dir)
        if (!inside(dir)) throw IOException("$dir leads out of the profile; nothing is written there")
    }

    /** Whether [path] resolves inside the profile's plugins folder. */
    private fun inside(path: Path): Boolean = PathProbe.real(path).startsWith(PathProbe.real(plugins()))

    private fun plugins(): Path = cache.parent

    /** [target], links to [source]'s entries, without the plugin's hooks unless they ride. */
    private fun fill(source: Path, target: Path) {
        entries(source).forEach { entry ->
            val name = entry.fileName.toString()
            when {
                name == Keys.HOOKS && !withHooks -> Unit
                // the manifest folder is filtered however it is reached, a link to one included; one that is no folder
                // is left out, since its hooks could not be taken out of it
                name == CLAUDE_PLUGIN_DIR && !withHooks ->
                    if (Files.isDirectory(entry)) manifest(entry, target.resolve(name))
                else -> Files.createSymbolicLink(target.resolve(name), entry)
            }
        }
    }

    /** The plugin's manifest folder with its manifest's `hooks` taken out and every other entry linked. */
    private fun manifest(source: Path, target: Path) {
        Files.createDirectories(target)
        entries(source).forEach { entry ->
            val name = entry.fileName.toString()
            val declared = if (name == PLUGIN_JSON_FILE) read(entry) else null
            if (declared != null) {
                val bare = JsonObject(declared - Keys.HOOKS)
                SecureFile.writeAtomic0600(target.resolve(name), json.encodeToString(JsonElement.serializer(), bare))
            } else if (name != PLUGIN_JSON_FILE) {
                Files.createSymbolicLink(target.resolve(name), entry)
            }
        }
    }

    /** What a mirror of [source] is built from: where it is, whether hooks ride, its entries, and its manifest when the
     *  mirror rewrites it. Two equal digests are two equal mirrors. */
    private fun digest(source: Path): String {
        val manifestDir = source.resolve(CLAUDE_PLUGIN_DIR)
        val rewritten = !withHooks && Files.isDirectory(manifestDir)
        val manifest = if (rewritten) {
            entries(manifestDir).map { it.fileName.toString() } +
                listOfNotNull(read(manifestDir.resolve(PLUGIN_JSON_FILE))?.toString())
        } else {
            emptyList()
        }
        val parts = listOf(source.toString(), withHooks.toString()) + entries(source).map { it.fileName.toString() } +
            manifest
        val text = parts.joinToString("\n").toByteArray(Charsets.UTF_8)
        val bytes = MessageDigest.getInstance(MIRROR_DIGEST_ALGORITHM).digest(text)
        return HexFormat.of().formatHex(bytes)
    }

    private fun entries(dir: Path): List<Path> =
        Files.list(dir).use { stream -> stream.limit(maxEntries.toLong()).sorted().toList() }

    private fun read(file: Path): JsonObject? = (DesktopSettingsRead(file).read() as? SettingsRead.Held)?.file
}

// An install record's path and version, as Claude Code writes them in installed_plugins.json.
private const val INSTALL_PATH = "installPath"
private const val INSTALL_VERSION = "version"

// The marketplace part of an id that names none.
private const val NO_MARKETPLACE = "unknown"

// How a mirror being built, and one waiting to go, are named in the staging folder.
private const val MIRROR_PREFIX = "mirror-"
private const val OLD_SUFFIX = "-old"

private const val MIRROR_DIGEST_ALGORITHM = "SHA-256"
