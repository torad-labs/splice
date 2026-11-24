// Resolves the live Splice MCP endpoint prefix, MCP-scoped access bearer, and [McpSharing] planner
// (honoring `[daemon] mcp_hosting`, `mcp_hosting_exclude`, and `mcp_share`) for harness sync.
package splice.app.mcp

import splice.app.MCP_HUB_FRONT_PORT
import splice.app.McpGlobalRead
import splice.app.cli.AdminSupport
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpBearer
import splice.client.mcp.McpRootsVerdicts
import splice.client.mcp.McpServerInput
import splice.client.mcp.McpShareChoice
import splice.client.mcp.McpSharing
import splice.client.mcp.TempleMcpEntry
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.mcp.McpUserRegistry
import splice.core.topology.DaemonConfig
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.topology.TopologyLoader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Builds [McpSharing] and endpoint prefixes for [McpHarnessSync]. */
internal class McpHarnessSharing(
    private val paths: StatePaths,
    private val env: EnvReader,
    private val log: LogSink,
    private val seams: McpHarnessSyncSeams,
) {
    /** The servers every harness is given, for [home]: what the person imported into Splice, `~/.claude.json`'s and
     *  the Temple's. */
    fun inputs(home: Path): List<McpServerInput> =
        McpGlobalRead(home, log, TempleMcpEntry(home, env, paths), McpUserRegistry(paths)).inputs()

    fun endpointPrefix(): String {
        val useFront = seams.frontLive.live(paths.stateDir, MCP_HUB_FRONT_PORT)
        val port = if (useFront) MCP_HUB_FRONT_PORT else seams.controlPortOverride ?: AdminSupport.controlPort(env)
        return "http://127.0.0.1:$port/mcp/"
    }

    fun planner(endpointPrefix: String): McpSharing {
        val daemon = readDaemonConfig()
        val enabled = daemon?.mcpHosting ?: true
        val exclude = daemon?.mcpHostingExclude?.toSet().orEmpty()
        val share = McpShareChoice { daemon?.mcpShare ?: true }
        val bearer = seams.bearerOverride ?: defaultBearer()
        return McpSharing(
            enabled = enabled,
            exclude = exclude,
            endpointPrefix = endpointPrefix,
            bearer = bearer,
            rootsBound = McpRootsVerdicts(paths.stateDir.resolve("mcp-roots-bound"), log),
            shareByDefault = share,
        )
    }

    private fun defaultBearer(): McpBearer {
        val mgmtKey = MgmtKey(paths)
        return McpAccessKey(
            stateDir = paths.stateDir,
            managementFile = paths.mgmtKeyFile,
            log = log,
            source = mgmtKey::get,
        )
    }

    private fun readDaemonConfig(): DaemonConfig? = try {
        val path = TopologyLoader.configPath(env)
        if (Files.isRegularFile(path)) TopologyLoader.parse(Files.readString(path)).daemon else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
