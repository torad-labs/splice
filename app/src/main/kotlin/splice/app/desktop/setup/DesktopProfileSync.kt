// NEW: desktop-policy. Seamless first-run migration of safe, non-auth user setup from a user's
// existing 1P Claude Desktop (`~/.config/Claude/` on Linux or `~/Library/Application Support/Claude/`
// on macOS) and Claude Code stdio MCP servers (`~/.claude.json`) into a 3P gateway profile
// (`<profile>/Claude-3p/`). Only fills missing entries; never overwrites existing 3P settings,
// never follows symlinks, and never reads cookies, credentials, or keyring stores.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class DesktopProfileSync(
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val files: DesktopLibraryFiles = DesktopLibraryFiles(),
) {
    private val extensionsSync = DesktopExtensionsSync(files)

    /** Migrates safe non-auth settings, compatible stdio MCP servers, and missing 1P Desktop
     *  extensions into [targetAppData] (e.g. `~/.config/claude-splice/Claude-3p`). Returns true
     *  if any file under [targetAppData] was created or updated. */
    fun syncInto(targetAppData: Path): Boolean {
        val configChanged = syncDesktopConfig(targetAppData)
        val extensionsChanged = extensionsSync.sync(sourceAppDataPath(), targetAppData)
        return configChanged || extensionsChanged
    }

    internal fun sourceAppDataPath(): Path {
        val base = if (osName.startsWith("Mac", ignoreCase = true)) {
            UserHome.dir(env).resolve("Library/Application Support")
        } else {
            env(SYNC_XDG_CONFIG_HOME)?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: UserHome.dir(env).resolve(".config")
        }
        return base.resolve(DEFAULT_CLAUDE_DIR)
    }

    internal fun sourceConfigPath(): Path = sourceAppDataPath().resolve(DESKTOP_CONFIG_FILE)

    internal fun mergeConfig(
        current: JsonObject,
        upstreamDesktop: JsonObject?,
        cliStdioServers: JsonObject = JsonObject(emptyMap()),
    ): JsonObject {
        val additions = mutableMapOf<String, JsonElement>()
        val combinedServers = JsonObject(
            cliStdioServers + (upstreamDesktop?.get(KEY_MCP_SERVERS) as? JsonObject).orEmpty(),
        )
        JsonMapMerger.mergeMissing(current, combinedServers, KEY_MCP_SERVERS)?.let { additions[KEY_MCP_SERVERS] = it }
        JsonMapMerger.mergeMissing(current, upstreamDesktop?.get(KEY_PREFERENCES) as? JsonObject, KEY_PREFERENCES)
            ?.let { additions[KEY_PREFERENCES] = it }
        SCALAR_KEYS.filter { it !in current }.forEach { scalarKey ->
            upstreamDesktop?.get(scalarKey)?.let { additions[scalarKey] = it }
        }
        return if (additions.isEmpty()) current else JsonObject(current + additions)
    }

    private fun syncDesktopConfig(targetAppData: Path): Boolean {
        val source = sourceConfigPath()
        val target = targetAppData.resolve(DESKTOP_CONFIG_FILE)
        val sameFile = source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()
        val upstreamDesktop = if (!sameFile && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            files.read(source)
        } else {
            null
        }
        val cliStdioServers = readClaudeCodeStdioServers()
        val current = files.read(target) ?: JsonObject(emptyMap())
        val merged = if (upstreamDesktop == null && cliStdioServers.isEmpty()) {
            current
        } else {
            mergeConfig(current, upstreamDesktop, cliStdioServers)
        }
        if (merged == current) return false
        Files.createDirectories(targetAppData)
        files.write(target, merged)
        return true
    }

    private fun readClaudeCodeStdioServers(): JsonObject {
        val claudeJson = UserHome.dir(env).resolve(SYNC_CLAUDE_JSON)
        val raw = if (Files.isRegularFile(claudeJson, LinkOption.NOFOLLOW_LINKS)) {
            files.read(claudeJson)?.get(KEY_MCP_SERVERS) as? JsonObject
        } else {
            null
        }
        val stdio = buildMap<String, JsonElement> {
            raw.orEmpty().forEach { (name, value) ->
                sanitizeStdioServer(value as? JsonObject)?.let { put(name, it) }
            }
        }
        return JsonObject(stdio)
    }

    /** Retains only stdio MCP server fields that Claude Desktop's `LocalMcpServerManager` spawns;
     *  skips HTTP/SSE remote servers (`url` without `command`) which Desktop cannot spawn here. */
    private fun sanitizeStdioServer(server: JsonObject?): JsonObject? {
        val command = JsonScalars.str(server, SYNC_MCP_COMMAND)?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put(SYNC_MCP_COMMAND, JsonPrimitive(command))
            (server?.get(SYNC_MCP_ARGS) as? JsonArray)?.let { put(SYNC_MCP_ARGS, it) }
            (server?.get(SYNC_MCP_ENV) as? JsonObject)?.let { put(SYNC_MCP_ENV, it) }
            JsonScalars.str(server, SYNC_MCP_CWD)?.takeIf { it.isNotBlank() }?.let {
                put(SYNC_MCP_CWD, JsonPrimitive(it))
            }
        }
    }
}

private class DesktopExtensionsSync(private val files: DesktopLibraryFiles) {
    fun sync(sourceAppData: Path, targetAppData: Path): Boolean {
        val srcExtRoot = sourceAppData.resolve(SYNC_EXTENSIONS_DIR)
        val sameRoot = sourceAppData.toAbsolutePath().normalize() == targetAppData.toAbsolutePath().normalize()
        val extDirs = if (!sameRoot && Files.isDirectory(srcExtRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(srcExtRoot).use { stream ->
                stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }
        } else {
            emptyList()
        }
        if (extDirs.isEmpty()) return false
        val copiedAny = extDirs.fold(false) { changed, srcDir ->
            val copied = copyMissingExtension(srcDir, sourceAppData, targetAppData)
            changed || copied
        }
        val registryChanged = syncRegistry(sourceAppData, targetAppData)
        return copiedAny || registryChanged
    }

    private fun copyMissingExtension(srcDir: Path, sourceAppData: Path, targetAppData: Path): Boolean {
        val extId = srcDir.fileName.toString()
        val dstDir = targetAppData.resolve(SYNC_EXTENSIONS_DIR).resolve(extId)
        val extCopied = if (!Files.exists(dstDir, LinkOption.NOFOLLOW_LINKS)) {
            copyDirectoryTree(srcDir, dstDir)
        } else {
            false
        }
        val srcSetting = sourceAppData.resolve(SYNC_EXTENSIONS_SETTINGS_DIR).resolve("$extId.json")
        val dstSetting = targetAppData.resolve(SYNC_EXTENSIONS_SETTINGS_DIR).resolve("$extId.json")
        val settingCopied = copyMissingFile(srcSetting, dstSetting)
        return extCopied || settingCopied
    }

    private fun copyDirectoryTree(srcDir: Path, dstDir: Path): Boolean {
        var copied = 0
        Files.walk(srcDir, SYNC_MAX_EXTENSION_DEPTH).use { stream ->
            val entries = stream.iterator()
            while (entries.hasNext() && copied < SYNC_MAX_EXTENSION_FILES) {
                val entry = entries.next()
                val rel = srcDir.relativize(entry)
                val safeRegular = Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) &&
                    !hasSymlinkSegment(srcDir, rel)
                if (safeRegular && copyMissingFile(entry, dstDir.resolve(rel))) copied++
            }
        }
        return copied > 0
    }

    private fun hasSymlinkSegment(root: Path, relative: Path): Boolean {
        var cursor = root
        return relative.any { segment ->
            cursor = cursor.resolve(segment)
            Files.isSymbolicLink(cursor)
        }
    }

    private fun copyMissingFile(src: Path, dst: Path): Boolean {
        val canCopy = Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS) &&
            !Files.exists(dst, LinkOption.NOFOLLOW_LINKS)
        if (!canCopy) return false
        dst.parent?.let(Files::createDirectories)
        Files.copy(src, dst)
        return true
    }

    private fun syncRegistry(sourceAppData: Path, targetAppData: Path): Boolean {
        val srcFile = sourceAppData.resolve(SYNC_EXTENSIONS_REGISTRY)
        val srcExts = if (Files.isRegularFile(srcFile, LinkOption.NOFOLLOW_LINKS)) {
            files.read(srcFile)?.get(SYNC_EXTENSIONS_KEY) as? JsonObject
        } else {
            null
        }
        val targetExtDir = targetAppData.resolve(SYNC_EXTENSIONS_DIR)
        val eligible = srcExts.orEmpty().filterKeys { extId ->
            Files.isDirectory(targetExtDir.resolve(extId), LinkOption.NOFOLLOW_LINKS)
        }
        val dstFile = targetAppData.resolve(SYNC_EXTENSIONS_REGISTRY)
        val current = files.read(dstFile) ?: JsonObject(emptyMap())
        val mergedExts = JsonMapMerger.mergeMissing(current, JsonObject(eligible), SYNC_EXTENSIONS_KEY) ?: return false
        Files.createDirectories(targetAppData)
        files.write(dstFile, JsonObject(current + (SYNC_EXTENSIONS_KEY to mergedExts)))
        return true
    }
}

private object JsonMapMerger {
    fun mergeMissing(current: JsonObject, src: JsonObject?, key: String): JsonObject? {
        val entries = src?.takeIf { it.isNotEmpty() } ?: return null
        val held = when {
            key !in current -> JsonObject(emptyMap())
            current[key] is JsonObject -> current[key] as JsonObject
            else -> return null
        }
        val missing = entries.filterKeys { it !in held }
        return if (missing.isEmpty()) null else JsonObject(held + missing)
    }
}

private const val SYNC_XDG_CONFIG_HOME = "XDG_CONFIG_HOME"
private const val DEFAULT_CLAUDE_DIR = "Claude"
private const val DESKTOP_CONFIG_FILE = "claude_desktop_config.json"
private const val SYNC_CLAUDE_JSON = ".claude.json"
private const val SYNC_EXTENSIONS_DIR = "Claude Extensions"
private const val SYNC_EXTENSIONS_SETTINGS_DIR = "Claude Extensions Settings"
private const val SYNC_EXTENSIONS_REGISTRY = "extensions-installations.json"
private const val SYNC_EXTENSIONS_KEY = "extensions"
private const val KEY_MCP_SERVERS = "mcpServers"
private const val KEY_PREFERENCES = "preferences"
private const val SYNC_MCP_COMMAND = "command"
private const val SYNC_MCP_ARGS = "args"
private const val SYNC_MCP_ENV = "env"
private const val SYNC_MCP_CWD = "cwd"
private val SCALAR_KEYS = listOf("coworkUserFilesPath", "isHardwareAccelerationDisabled")

// why: bounds recursive extension directory copy depth so malformed or deeply nested trees cannot stall startup.
private const val SYNC_MAX_EXTENSION_DEPTH = 12

// why: bounds per-extension file count copied from a 1P profile into a fresh 3P profile.
private const val SYNC_MAX_EXTENSION_FILES = 2_000
