package splice.client.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import splice.client.ClaudeHomes
import splice.client.Keys
import splice.core.config.StatePaths
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import java.nio.file.Files
import java.nio.file.Path

public const val TEMPLE_MCP_NAME: String = "temple"
public const val TEMPLE_MCP_PROVENANCE_KEY: String = "provenance"
public const val TEMPLE_MCP_REGISTERED_SERVICE: String = "registered_service"

/** The installed Temple stdio entry, shared by daemon boot and every client's launch plan. The app is not queried:
 *  its local MCP process must be discoverable before the app starts. Existing declarations keep their exact command,
 *  arguments and environment, including older Temple entries with a larger tool surface. */
public class TempleMcpEntry(
    private val home: Path,
    private val env: EnvReader = EnvReader(System::getenv),
    paths: StatePaths = StatePaths(envReader = env, homeDir = home),
) {
    private val json = SourceJson()
    private val homes = ClaudeHomes(home)
    private val fallback by lazy { declared() ?: fromEnvironment() }

    public val registrationFile: Path = paths.stateDir.resolve("mcp-services").resolve("temple.json")

    public fun registration(): JsonObject = json.topLevel(registrationFile)

    public fun server(): McpPlannedServer? {
        val registration = registration()
        val entry = registration["server"] as? JsonObject
        return entry?.takeIf(::stdio)?.let {
            McpPlannedServer(
                TEMPLE_MCP_NAME,
                McpPlanScope.USER,
                registrationFile,
                it,
                provenance = provenance(registration),
            )
        } ?: fallback
    }

    /** One source snapshot preserves the state registration's provenance through sharing decisions. */
    public fun inputs(): List<McpServerInput> = server()?.let {
        listOf(McpServerInput(it.name, it.entry, it.provenance))
    }.orEmpty()

    public fun entries(): JsonObject = JsonObject(
        inputs().associate { it.name to it.entry },
    )

    private fun provenance(registration: JsonObject): McpServerProvenance =
        if (JsonScalars.str(registration, TEMPLE_MCP_PROVENANCE_KEY) == TEMPLE_MCP_REGISTERED_SERVICE) {
            McpServerProvenance.REGISTERED_SERVICE
        } else {
            McpServerProvenance.CLIENT
        }

    private fun declared(): McpPlannedServer? {
        val files = (listOf(home.resolve(Keys.CLAUDE_JSON)) + homes.claudeJsonFiles()).distinct()
        return files.firstNotNullOfOrNull { file ->
            val servers = json.topLevel(file)[Keys.MCP_SERVERS] as? JsonObject
            val entry = servers?.get(TEMPLE_MCP_NAME) as? JsonObject
            entry?.takeIf(::stdio)?.let { McpPlannedServer(TEMPLE_MCP_NAME, McpPlanScope.USER, file, it) }
        }
    }

    private fun stdio(entry: JsonObject): Boolean {
        val type = JsonScalars.str(entry, "type")
        return (type == null || type == "stdio") && !JsonScalars.str(entry, "command").isNullOrBlank()
    }

    private fun fromEnvironment(): McpPlannedServer? {
        val script = env("TEMPLE_TOOLS_SERVER")?.let(PathProbe::spelled)
            ?: env("TEMPLE_ROOT")?.let(PathProbe::spelled)?.resolve("slices/screen/bin/tools-server.ts")
        if (script == null || !Files.isRegularFile(script)) return null
        val entry = buildJsonObject {
            put("command", "bun")
            put("args", JsonArray(listOf(JsonPrimitive(script.toString()))))
            env("TEMPLE_URL")?.takeIf { it.isNotBlank() }?.let { url ->
                putJsonObject("env") { put("TEMPLE_URL", url) }
            }
        }
        return McpPlannedServer(TEMPLE_MCP_NAME, McpPlanScope.USER, script, entry)
    }
}
