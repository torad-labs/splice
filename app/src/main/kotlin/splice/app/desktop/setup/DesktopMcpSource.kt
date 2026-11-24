// NEW: desktop-3p-parity (Nov 23, 2026). The MCP servers Claude Desktop on the gateway (`Claude-3p`) should have: the
// servers every other harness is given (what the person imported into Splice, `~/.claude.json`'s, the Temple's), as
// Splice plans them. Desktop starts only stdio servers, so each server it can start itself is given as a command, and
// each one Splice hosts or the person reaches at a URL goes through the stdio bridge (`splice mcp stdio <name>`),
// named only by its name: no address, header or key is ever written into Desktop's configuration.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import splice.app.mcp.McpHarnessSharing
import splice.app.mcp.McpHarnessSyncSeams
import splice.client.mcp.McpBearer
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.mcp.McpHarness
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.topology.TopologyStatePaths
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

/** What Desktop should have: the [stdio] servers it starts itself, each as Desktop's `mcpServers` spells one, and the
 *  servers it reaches through the bridge, by name ([bridged]), through the bridge [program] in splice's state folder
 *  (none when nothing is bridged and splice laid no bridge entries to take out). */
internal data class DesktopMcpWanted(
    val stdio: JsonObject = JsonObject(emptyMap()),
    val bridged: List<String> = emptyList(),
    val program: DesktopPrograms? = null,
)

/** Where [DesktopProfileSync] learns which MCP servers Desktop should have. */
internal fun interface DesktopMcpSource {
    fun wanted(): DesktopMcpWanted
}

/** Splice's MCP servers, planned as every harness is given them, for the home and state folder [env] names unless
 *  given them, read afresh at each [wanted]. */
internal class SpliceDesktopMcp(
    private val env: EnvReader,
    private val homeAt: Path? = null,
    private val pathsAt: StatePaths? = null,
) : DesktopMcpSource {
    override fun wanted(): DesktopMcpWanted {
        val home = homeAt ?: UserHome.dir(env)
        val paths = pathsAt ?: TopologyStatePaths(env, home).current()
        // Only which servers are hosted matters here, never the key a hosted entry would carry: the bridge reads it
        // when Desktop starts it, so planning reads no key and mints none.
        val seams = McpHarnessSyncSeams(bearerOverride = McpBearer { "" })
        val sharing = McpHarnessSharing(paths, env, LogSink {}, seams)
        val plan = sharing.planner(PLANNED_PREFIX).plan(sharing.inputs(home), McpHarness.CLAUDE.wire)
        val stdio = LinkedHashMap<String, JsonElement>()
        val bridged = mutableListOf<String>()
        plan.rewritten.forEach { (name, entry) ->
            val server = entry as? JsonObject
            val url = JsonScalars.str(server, MCP_URL)
            when {
                url.isNullOrBlank() -> started(server)?.let { stdio[name] = it }
                // Only what the bridge can reach: an http or https address with a host, as McpStdioTargets takes it.
                web(url) -> bridged += name
            }
        }
        return DesktopMcpWanted(JsonObject(stdio), bridged, DesktopPrograms(paths.stateDir))
    }

    /** Whether [url] is an http or https address with a host. */
    private fun web(url: String): Boolean = try {
        URI(url).let { it.scheme?.lowercase() in BRIDGED_SCHEMES && !it.host.isNullOrBlank() }
    } catch (_: URISyntaxException) {
        false
    }

    /** [server] as Desktop's `LocalMcpServerManager` starts one: its command, arguments, environment and folder; null
     *  for one with no command. */
    private fun started(server: JsonObject?): JsonObject? {
        val command = JsonScalars.str(server, MCP_COMMAND)?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject {
            put(MCP_COMMAND, JsonPrimitive(command))
            (server?.get(MCP_ARGS) as? JsonArray)?.let { put(MCP_ARGS, it) }
            (server?.get(MCP_ENV) as? JsonObject)?.let { put(MCP_ENV, it) }
            JsonScalars.str(server, MCP_CWD)?.takeIf { it.isNotBlank() }?.let { put(MCP_CWD, JsonPrimitive(it)) }
        }
    }
}

// The schemes the stdio bridge speaks.
private val BRIDGED_SCHEMES = setOf("http", "https")

// The hub's address does not matter to this plan, only which servers it hosts.
private const val PLANNED_PREFIX = "http://127.0.0.1/mcp/"

/** An MCP server entry's keys, in Claude Code's config and Desktop's alike. */
internal const val MCP_COMMAND: String = "command"
internal const val MCP_ARGS: String = "args"
private const val MCP_ENV = "env"
private const val MCP_CWD = "cwd"
private const val MCP_URL = "url"
