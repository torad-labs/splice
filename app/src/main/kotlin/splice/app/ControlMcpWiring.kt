// NEW: the control plane's MCP host wiring, moved out of ControlPlane.kt by its own seam, behavior unchanged
// (concentration, Oct 13, splice-lead): the sharing planner, the host and its hub, the four live knob readers, and the
// four values the console and /health read once the host is built. ControlPlane holds one and asks it at start.
package splice.app

import splice.app.control.McpServiceRefresh
import splice.app.control.api.McpHubVerdict
import splice.app.mcp.TempleMcpStartup
import splice.app.sources.mcp.McpAttached
import splice.client.mcp.McpHubLive
import splice.client.mcp.McpInventory
import splice.client.mcp.McpRootsVerdicts
import splice.client.mcp.McpSharing
import splice.client.mcp.TempleMcpEntry
import splice.control.mcp.APP_MCP_SLICE
import splice.control.mcp.McpHandshakeBudget
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.control.mcp.McpIdleBudget
import splice.control.mcp.McpRequestBudget
import splice.control.mcp.McpServerCeiling
import splice.core.config.Knob
import splice.launch.LaunchSpec
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

internal class ControlMcpWiring(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
    /** v0.4.0 shared MCP hosting knobs ([daemon] mcp_hosting / mcp_hosting_exclude). */
    private val mcpHosting: McpHostingSettings,
) {
    // Views over [daemon], never copies: a stored copy would fix the value at construction.
    private val statePaths get() = daemon.statePaths
    private val config get() = daemon.config
    private val log get() = daemon.log

    /** The census the MCP host reports, kept for GET /api/mcp/memory, which names the copies it measures by it. */
    var inventory: McpInventory? = null
        private set
    var sharing: McpSharing? = null
        private set

    /** The sessions the hub binds to each server, kept for GET /api/mcp/memory beside the census. */
    var attached: McpAttached? = null
        private set

    /** Whether the MCP hub runs, decided once as [host] builds it; /health reports it for doctor. */
    var hub: McpHubVerdict? = null
        private set

    /** The hub is live only on a verdict that says on: no verdict, as when MCP is not hosted, is off. */
    val hubLive = McpHubLive { hub?.let { it.offReason == null } == true }

    /** The daemon's one MCP sharing planner. §8 8c: a server seen asking for roots stays per-session after a restart,
     *  so no session loses it mid-work; the verdicts live in the state dir. */
    fun planner(controlPort: Int): McpSharing = McpSharing(
        enabled = mcpHosting.enabled,
        exclude = mcpHosting.exclude,
        endpointPrefix = "http://127.0.0.1:$controlPort/mcp/",
        bearer = daemon.mcpAccess,
        rootsBound = McpRootsVerdicts(statePaths.stateDir.resolve("mcp-roots-bound"), log),
        shareByDefault = mcpHosting.share,
    )

    /** V4-146: one McpGlobalRead instance for both the rewrite pipeline and the census — a second
     *  one would double the "malformed"/"unreadable" diagnostics on the same file. Split out of
     *  ControlPlane.start to keep that budget; the census only WIDENS what `/api/mcp` can report, it never
     *  changes what [sharing] rewrites (see McpHostingWiring.kt's header). */
    fun host(home: Path, sharing: McpSharing, launchSpecs: List<LaunchSpec>): McpHost {
        val globalRead = McpGlobalRead(home, log, TempleMcpEntry(home, paths = statePaths))
        val inventory = McpInventoryWiring(home, sharing, globalRead, launchSpecs).inventory
        this.inventory = inventory
        this.sharing = sharing
        val config = hostConfig()
        // MCP hub steps 3 and 4: the daemon half the hub front calls; the host's sweep stops ended sessions' copies.
        val wiring = McpHubWiring(globalRead, statePaths.stateDir)
        val hub = wiring.hub(
            sharing,
            config.initializeTimeout,
            ProcessDispatchers().io(),
            log,
            daemon.mcpAccess,
        )
        this.hub = McpHubVerdict(wiring.offReason)
        attached = McpAttached { hub.attached() }
        return McpHost(
            sharing,
            globalRead,
            config = config,
            log = log,
            inventory = inventory,
            hub = hub,
        )
    }

    fun services(host: McpHost, home: Path): McpServiceRefresh = McpServiceRefresh {
        val frontPort = MCP_HUB_FRONT_PORT.takeIf { hubLive.live() }
        TempleMcpStartup(statePaths, log).refresh(host, home, frontPort)
    }

    /** V4-110: the shared MCP host's four lifecycle values, each a READER over the knob layer (daemon-global)
     *  rather than a number, because each has an enforcement point the running host reaches again — the idle
     *  sweep, spawn admission, a child's handshake, a forwarded request. So all four move on an operator's PATCH
     *  and none of them is restartRequired. Absent knobs keep their declared defaults, and the McpHostConfig
     *  defaults are the same numbers, kept in one place (the Knob enum) rather than restated here. The slice is
     *  the one value still read once: it is a name, and it is read again per spawn from this one string. */
    private fun hostConfig(): McpHostConfig = McpHostConfig(
        idleTimeout = McpIdleBudget { countNow(Knob.MCP_IDLE_TIMEOUT_MS).milliseconds },
        maxServers = McpServerCeiling { countNow(Knob.MCP_MAX_SERVERS).toInt() },
        requestTimeout = McpRequestBudget { countNow(Knob.MCP_REQUEST_TIMEOUT_MS).milliseconds },
        initializeTimeout = McpHandshakeBudget { countNow(Knob.MCP_INITIALIZE_TIMEOUT_MS).milliseconds },
        // V4-176: the slice is a NAME, not a duration, and a blank one would spawn every hosted
        // child into a `--slice=` systemd rejects. An empty knob therefore falls back to the
        // declared default rather than being passed through.
        slice = (config.getConfig().asMap()[Knob.MCP_SLICE.key] as? String)?.takeIf { it.isNotBlank() }
            ?: APP_MCP_SLICE,
    )

    /** One knob's number off the config AS IT STANDS NOW, falling back to the knob's declared default when the
     *  merge left the key absent. Every reader above goes through this, which is what makes them live: the host
     *  holds the lambda, never the number, so there is no snapshot to go stale. */
    private fun countNow(knob: Knob): Long = (config.getConfig().asMap()[knob.key] as? Long) ?: knob.count()
}
