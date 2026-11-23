// Read-only discovery of user MCP servers across Claude Code, Claude Desktop, Antigravity (agy), and Codex.
// Never logs env vars, HTTP headers, or URL query strings. Skips circular Splice-hosted loopback entries.
package splice.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.client.Keys
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryRecord
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val CLAUDE_DESKTOP_CONFIG_FILE = "claude_desktop_config.json"

/** One MCP server discovered on disk from a harness config file. */
internal data class DiscoveredMcpServer(
    val name: String,
    val source: McpImportSource,
    val sourceFile: Path,
    val entry: JsonObject,
) {
    fun toRecord(importedAt: String): McpRegistryRecord = McpRegistryRecord(name, entry, source, importedAt)
}

/** Resolves harness config paths and reads user-declared MCP servers across Claude, agy, and Codex. */
internal class McpHarnessDiscovery(
    private val home: Path,
    private val env: EnvReader = EnvReader(System::getenv),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val codexToml = CodexMcpTomlCodec()
    private val normalizer = McpEntryNormalizer()

    fun claudeCodeFile(): Path = home.resolve(Keys.CLAUDE_JSON)

    fun claudeDesktopFile(): Path {
        val os = osName.lowercase()
        return when {
            "mac" in os || "darwin" in os ->
                home.resolve("Library").resolve("Application Support").resolve("Claude")
                    .resolve(CLAUDE_DESKTOP_CONFIG_FILE)
            "win" in os -> {
                val appData = env("APPDATA")?.takeIf { it.isNotBlank() }?.let(PathProbe::spelled)
                    ?: home.resolve("AppData").resolve("Roaming")
                appData.resolve("Claude").resolve(CLAUDE_DESKTOP_CONFIG_FILE)
            }
            else -> {
                val xdg = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(PathProbe::spelled)
                    ?: home.resolve(".config")
                xdg.resolve("Claude").resolve(CLAUDE_DESKTOP_CONFIG_FILE)
            }
        }
    }

    fun agyConfigFile(): Path = home.resolve(".gemini").resolve("config").resolve("mcp_config.json")

    fun codexHomeDir(): Path =
        env("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(PathProbe::spelled) ?: home.resolve(".codex")

    fun codexConfigFile(): Path = codexHomeDir().resolve("config.toml")

    /** Harnesses whose config directories or files already exist on this machine. */
    fun detectedHarnesses(): Set<McpHarness> = buildSet {
        if (Files.isRegularFile(claudeCodeFile()) || Files.isDirectory(home.resolve(UserHome.CLAUDE_DIR))) {
            add(McpHarness.CLAUDE)
        }
        if (Files.isDirectory(home.resolve(".gemini")) || Files.isRegularFile(agyConfigFile())) {
            add(McpHarness.AGY)
        }
        if (Files.isDirectory(codexHomeDir()) || Files.isRegularFile(codexConfigFile())) {
            add(McpHarness.CODEX)
        }
    }

    /** Discovers all user MCP servers from [sources] in priority order. */
    fun discover(sources: List<McpImportSource> = defaultSources()): List<DiscoveredMcpServer> =
        sources.flatMap(::discoverSource)

    fun defaultSources(): List<McpImportSource> = listOf(
        McpImportSource.CLAUDE_CODE,
        McpImportSource.CLAUDE_DESKTOP,
        McpImportSource.AGY,
        McpImportSource.CODEX,
    )

    private fun discoverSource(source: McpImportSource): List<DiscoveredMcpServer> = when (source) {
        McpImportSource.CLAUDE_CODE -> readJsonServers(source, claudeCodeFile(), agyShape = false)
        McpImportSource.CLAUDE_DESKTOP -> readJsonServers(source, claudeDesktopFile(), agyShape = false)
        McpImportSource.AGY -> readJsonServers(source, agyConfigFile(), agyShape = true)
        McpImportSource.CODEX -> readCodexServers(codexConfigFile())
        McpImportSource.MANUAL -> emptyList()
    }

    private fun readJsonServers(
        source: McpImportSource,
        file: Path,
        agyShape: Boolean,
    ): List<DiscoveredMcpServer> {
        val root = readJsonObject(file) ?: return emptyList()
        val servers = root[Keys.MCP_SERVERS] as? JsonObject ?: return emptyList()
        return servers.entries.mapNotNull { (name, value) ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val canonical = normalizer.normalize(obj, agyShape) ?: return@mapNotNull null
            DiscoveredMcpServer(name, source, file, canonical)
        }
    }

    private fun readCodexServers(file: Path): List<DiscoveredMcpServer> {
        if (!Files.isRegularFile(file)) return emptyList()
        val text = try {
            Files.readString(file)
        } catch (_: IOException) {
            return emptyList()
        }
        return codexToml.parse(text).entries.mapNotNull { (name, entry) ->
            if (normalizer.isSpliceLoopbackEntry(entry)) {
                null
            } else {
                DiscoveredMcpServer(name, McpImportSource.CODEX, file, entry)
            }
        }
    }

    private fun readJsonObject(file: Path): JsonObject? {
        if (!Files.isRegularFile(file)) return null
        return try {
            JsonScalars.objectOrNull(json, Files.readString(file))
        } catch (_: IOException) {
            null
        }
    }
}
