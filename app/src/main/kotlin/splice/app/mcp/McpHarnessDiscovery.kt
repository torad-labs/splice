// Read-only discovery of user MCP servers across Claude Code, Claude Desktop, Antigravity (agy), and Codex.
// Never logs env vars, HTTP headers, or URL query strings. Skips circular Splice-hosted loopback entries.
package splice.app.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.app.MCP_HUB_FRONT_PORT
import splice.app.cli.AdminSupport
import splice.core.config.Knob
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.mcp.McpImportSource
import splice.core.mcp.McpRegistryRecord
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val CLAUDE_DESKTOP_CONFIG_FILE = "claude_desktop_config.json"
private const val DISCOVERY_CLAUDE_JSON = ".claude.json"
private const val DISCOVERY_SERVERS_KEY = "mcpServers"
private const val GEMINI_DIR = ".gemini"
private const val AGY_BINARY = "agy"

// The folders agy keeps under ~/.gemini, which Gemini CLI does not.
private val AGY_FOLDERS = listOf("antigravity", "antigravity-cli")

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
    private val normalizer = McpEntryNormalizer(
        setOf(MCP_HUB_FRONT_PORT, Knob.CONTROL_PORT.count().toInt(), AdminSupport.controlPort(env)),
    )

    fun claudeCodeFile(): Path = home.resolve(DISCOVERY_CLAUDE_JSON)

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

    fun agyConfigFile(): Path = home.resolve(GEMINI_DIR).resolve("config").resolve("mcp_config.json")

    fun codexHomeDir(): Path =
        env("CODEX_HOME")?.takeIf { it.isNotBlank() }?.let(PathProbe::spelled) ?: home.resolve(".codex")

    fun codexConfigFile(): Path = codexHomeDir().resolve("config.toml")

    /** Harnesses whose config directories or files already exist on this machine. */
    fun detectedHarnesses(): Set<McpHarness> = buildSet {
        if (Files.isRegularFile(claudeCodeFile()) || Files.isDirectory(home.resolve(UserHome.CLAUDE_DIR))) {
            add(McpHarness.CLAUDE)
        }
        if (agyInstalled()) add(McpHarness.AGY)
        if (Files.isDirectory(codexHomeDir()) || Files.isRegularFile(codexConfigFile())) {
            add(McpHarness.CODEX)
        }
    }

    /** Whether Antigravity's `agy` is on this machine: on PATH, or its own folder under `~/.gemini`. Gemini CLI keeps
     *  `~/.gemini` too, and the mcp_config.json there may be one an earlier sync wrote, so neither alone says agy. */
    private fun agyInstalled(): Boolean {
        val gemini = home.resolve(GEMINI_DIR)
        if (AGY_FOLDERS.any { Files.isDirectory(gemini.resolve(it)) }) return true
        return env("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank).any { dir ->
            PathProbe.spelled(dir)?.resolve(AGY_BINARY)?.let(Files::isExecutable) == true
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
        val servers = root[DISCOVERY_SERVERS_KEY] as? JsonObject ?: return emptyList()
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
