// NEW: agy-desktop-parity-2 (docs/specs/desktop-antigravity.md). Reads MCP servers from Claude Code's config files:
// user and project-local servers from `.claude.json`, and approved repository `.mcp.json` servers with
// `${VAR:-default}` expansion. Respects `CLAUDE_CONFIG_DIR` and `--strict-mcp-config`.
package splice.provider.antigravity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import splice.core.config.UserHome
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Loads Claude Code's file-based MCP server declarations for [cwd]. */
internal class ClaudeMcpFiles(
    private val cwd: Path,
    private val env: EnvReader,
    private val log: LogSink,
) {
    private val reader = AgyMcpReader(log)

    fun load(strictMcpConfig: Boolean): List<AgyMcpSpec> {
        if (strictMcpConfig) return emptyList()
        val customConfig = env(CLAUDE_CONFIG_ENV)?.takeIf(String::isNotBlank)?.let(Path::of)
        val claudeJsonDir = customConfig ?: UserHome.home(env)
        val settingsDir = customConfig ?: UserHome.claudeDir(env)
        val claudeJson = readJson(claudeJsonDir.resolve(".claude.json"))
        val projectEntry = projectObject(claudeJson)

        val merged = LinkedHashMap<String, AgyMcpSpec>()
        reader.fromConfigMap(claudeJson?.get("mcpServers") as? JsonObject).forEach { merged[it.name] = it }
        reader.fromConfigMap(projectEntry?.get("mcpServers") as? JsonObject).forEach { merged[it.name] = it }
        loadRepoMcpJson(settingsDir, projectEntry).forEach { merged[it.name] = it }
        return merged.values.toList()
    }

    private fun loadRepoMcpJson(settingsDir: Path, projectEntry: JsonObject?): List<AgyMcpSpec> {
        val repoMcp = readJson(cwd.resolve(".mcp.json")) ?: return emptyList()
        val rawServers = repoMcp["mcpServers"] as? JsonObject ?: return emptyList()
        val settingsFiles = listOfNotNull(
            readJson(settingsDir.resolve("settings.json")),
            readJson(cwd.resolve(UserHome.CLAUDE_DIR).resolve("settings.json")),
            readJson(cwd.resolve(UserHome.CLAUDE_DIR).resolve("settings.local.json")),
            projectEntry,
        )
        val enabled = settingsFiles.flatMap { stringSet(it, "enabledMcpjsonServers") }.toSet()
        val disabled = settingsFiles.flatMap { stringSet(it, "disabledMcpjsonServers") }.toSet()
        val filtered = JsonObject(rawServers.filterKeys { it in enabled && it !in disabled })
        return reader.fromConfigMap(filtered, McpValueTransform(::expandVars))
    }

    private fun projectObject(claudeJson: JsonObject?): JsonObject? {
        val projects = claudeJson?.get("projects") as? JsonObject ?: return null
        val key = cwd.toString()
        return (projects[key] as? JsonObject) ?: (projects[cwd.toAbsolutePath().normalize().toString()] as? JsonObject)
    }

    private fun stringSet(obj: JsonObject, key: String): List<String> =
        (obj[key] as? JsonArray).orEmpty().mapNotNull(JsonScalars::str)

    private fun expandVars(text: String): String = ENV_VAR_PATTERN.replace(text) { match ->
        val name = match.groupValues[1]
        val hasDefault = match.groupValues[2].isNotEmpty()
        val defaultVal = match.groupValues[GROUP_DEFAULT_VALUE]
        env(name)?.takeIf(String::isNotEmpty) ?: if (hasDefault) defaultVal else ""
    }

    private fun readJson(path: Path): JsonObject? {
        if (!Files.isRegularFile(path)) return null
        return try {
            JsonScalars.objectOrNull(LENIENT_JSON, Files.readString(path))
        } catch (ignored: IOException) {
            log("[antigravity] could not read ${path.fileName}\n")
            null
        }
    }
}

private const val CLAUDE_CONFIG_ENV = "CLAUDE_CONFIG_DIR"
private const val GROUP_DEFAULT_VALUE = 3

private val ENV_VAR_PATTERN = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(:-([^}]*))?\}""")

private val LENIENT_JSON = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
