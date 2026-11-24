package splice.app.control.engine

import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RoutingNode
import kotlinx.serialization.json.JsonObject
import splice.app.ControlServerParts
import splice.app.DaemonEnvironment
import splice.app.control.ControlListen
import splice.app.control.ControlReadings
import splice.app.control.ControlRuntime
import splice.app.control.ControlServer
import splice.app.control.FailedHeads
import splice.app.control.RuntimeNotAnswering
import splice.app.control.TopologyDigest
import splice.app.control.TurnPathStalled
import splice.app.control.api.ControlHealthReport
import splice.app.control.api.HeadReadiness
import splice.app.control.api.HeadSignals
import splice.app.daemon.BootedTopology
import splice.app.modes.ModeClaims
import splice.client.mcp.McpBearer
import splice.client.mcp.McpSharing
import splice.configuration.topology.TopologyStale
import splice.control.mcp.GlobalMcpServers
import splice.control.mcp.McpHost
import splice.control.mcp.hub.McpHubOff
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.topology.TopologyFinding
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.modes.api.ModeCatalog
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute

/** Build the control plane's route tree without binding or activating any of its request-time services. */
internal class ControlRouteClaims(private val env: EnvReader) {
    private val quiet = LogSink {}

    fun of(catalog: ModeCatalog, paths: StatePaths): List<TopologyFinding> {
        if (catalog.modes.none { it.handler.routes.isNotEmpty() }) return emptyList()
        val config = ConfigService(paths, envReader = env, log = quiet)
        val daemon = DaemonEnvironment(paths, config, MgmtKey(paths, quiet), quiet)
        val guard = ControlServerParts(daemon, BootedTopology(), env).guard()
        val runtime = runtime(paths)
        val signals = HeadSignals(emptyMap(), RuntimeNotAnswering { emptyMap() })
        val health = ControlHealthReport(
            HeadReadiness(emptyMap(), FailedHeads { 0 }, 0, TurnPathStalled { emptyList() }),
            signals,
            TopologyDigest { "" },
            TopologyStale { false },
            runtime.clientVersions,
        )
        val readings = ControlReadings(health, signals)
        val server = ControlServer(ControlListen(0), emptyMap(), config, guard, quiet, readings, runtime)
        return claims(server, catalog)
    }

    /** Register the built-ins without an engine or application lifecycle. */
    private fun claims(server: ControlServer, catalog: ModeCatalog): List<TopologyFinding> {
        val root = RoutingNode(null, RootRouteSelector(), environment = applicationEnvironment())
        server.engineShell.registerBuiltIns(root)
        return ModeClaims(root).findings(catalog.modes)
    }

    /** Both registries are present in a real daemon even when empty. Include their routes, not their live resources. */
    private fun runtime(paths: StatePaths): ControlRuntime = ControlRuntime(
        sessions = SessionRegistry(paths.stateDir, RouteOfPid { SessionRoute.Unknown }),
        mcpHost = McpHost(
            McpSharing(enabled = false, emptySet(), "", McpBearer { "" }),
            GlobalMcpServers { JsonObject(emptyMap()) },
            log = quiet,
            hub = McpHubOff("route preflight"),
        ),
    )
}
