// PORT-OF: server/src/control/api.mjs + control-server.mjs @ pre-public-port-baseline — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head + the committed
// single-file dashboard at /. Single-daemon simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state. JSON payload shapes match console/src/shared/api/index.ts so the
// unmodified dashboard runs against this daemon (the P4-WEBUI contract).
//
// HD-24: split into splice.app.control.api (the HTTP surface — payload projections and by-name
// routes) + splice.app.control (this file: ctor/routing/lifecycle, plus ManagedHead/ControlPorts and
// the adapters that project ManagedHead into each feature's contract). One direction of real
// dependency: api -> domain.
//
// LAYOUT-01: the route table is one MOUNT per capability in splice.app.control.mount, each owning its
// feature's route objects and registering its own rows behind the one ControlGuard. This file keeps
// construction, lifecycle and the order the mounts register in. The table had reached 68 rows importing
// 37 slice packages, which made this one file every feature's edit.
package splice.app.control

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import splice.app.control.api.ControlAudit
import splice.app.control.api.ControlPayloads
import splice.app.control.api.HeadResolver
import splice.app.control.mount.AccountsMount
import splice.app.control.mount.ConfigurationMount
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.DiagnosticsMount
import splice.app.control.mount.EventsMount
import splice.app.control.mount.FleetMount
import splice.app.control.mount.LaunchMount
import splice.app.control.mount.LifecycleMount
import splice.app.control.mount.McpMount
import splice.app.control.mount.ModelsMount
import splice.app.control.mount.SessionsMount
import splice.app.control.mount.TurnsMount
import splice.app.control.mount.UsageMount
import splice.configuration.topology.TopologyStale
import splice.control.mcp.McpHost
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.launch.recipe.LaunchService
import splice.lifecycle.restart.ShutdownDaemon
import splice.sessions.registry.SessionSource

// ControlServer's lifecycle/limit constants, at their sanctioned file-scope home.
private const val STOP_GRACE_MS = 100L
private const val STOP_TIMEOUT_MS = 500L

public class ControlServer(
    private val port: Int,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: DashboardPage,
    private val log: LogSink,
    private val launchService: LaunchService? = null,
    private val shutdownDaemon: ShutdownDaemon = ShutdownDaemon {},
    // Live count of heads that failed to assemble or start (Daemon.start's `failed` map) — lets
    // the /health readyHeads protocol converge on a degraded boot instead of waiting forever for
    // a head that will never become ready (review 2026-07-22 round 3).
    private val failedHeads: FailedHeads = FailedHeads { 0 },
    // Total CONFIGURED heads (topology). The readyHeads + failedHeads == heads invariant only holds
    // against the configured total: an assembly-failed head is counted in failedHeads but is NEVER
    // in the `heads` map, so reporting heads.size broke the invariant for it (review 2026-07-23).
    private val configuredHeads: Int = heads.size,
    // JW-04: the booted config identity + a per-request staleness recompute (fail-open lambda).
    // Topology stays deliberately non-hot-reloadable; these only make the required restart VISIBLE
    // to the shim, doctor, and the dashboard. V4-162: the context windows are re-read live, so the
    // digest names the version the daemon RUNS and is read per request like the staleness.
    private val topologyDigest: TopologyDigest = TopologyDigest { "" },
    private val configPath: String = "",
    private val topologyStale: TopologyStale = TopologyStale { false },
    private val turnPathStalled: TurnPathStalled = TurnPathStalled { emptyList() },
    /** v0.4.0 shared MCP hosting; null keeps the control plane exactly as before. */
    private val mcpHost: McpHost? = null,
    /** v0.4.0 (FEATURES.md §4): the Claude Code session registry, read-only, for /api/sessions. */
    sessions: SessionSource? = null,
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
) {
    /** The nine ports ControlPlane wires after construction — see [ConsolePorts], which carries the
     *  discipline they share and why they left this file (V4-161). Read at CALL time, never captured. */
    public val ports: ConsolePorts = ConsolePorts()

    private val payloads =
        ControlPayloads(
            heads,
            failedHeads,
            configuredHeads,
            topologyDigest,
            configPath,
            topologyStale,
            turnPathStalled,
            clientVersions,
        )
    private val resolver = HeadResolver(heads, payloads)
    private val audit = ControlAudit(log)
    private val guard = ControlGuard(mgmtKey, audit)

    // One mount per capability. Every mount reads [ports] at CALL time, never at construction:
    // ControlPlane assigns them after this server exists, so a captured port would be null forever.
    private val fleet = FleetMount(payloads, resolver, audit, dashboardHtml, guard)
    private val lifecycle = LifecycleMount(payloads, shutdownDaemon, ports, guard)
    private val configuration = ConfigurationMount(config, topologyStale, ports, guard)
    private val usage = UsageMount(heads, resolver, config, clientVersions, ports, guard)
    private val accounts = AccountsMount(heads, resolver, ports, guard)
    private val turns = TurnsMount(heads, resolver, config, ports, guard)
    private val sessionMount = SessionsMount(sessions, heads, config, ports, guard)
    private val events = EventsMount(ports, guard)
    private val diagnostics = DiagnosticsMount(resolver, ports, guard)
    private val models = ModelsMount(heads, ports, guard)
    private val launch = LaunchMount(heads, resolver, launchService, audit, log, guard)
    private val mcp = mcpHost?.let { McpMount(it, guard) }

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    /** What the connector actually bound in the current [start]; null while stopped. */
    @Volatile
    private var boundPort: Int? = null

    /** The port this control plane listens on: the one its connector BOUND while running — the
     *  OS-assigned one when it was constructed with port 0 — and the configured [port] otherwise.
     *  A caller that wants a free port passes 0 and reads this after [start]: leasing a number with
     *  ServerSocket(0) and handing it here to bind later leaves a window in which anything else may
     *  take it (the BindException class of CI run 35881955038). */
    public val listeningPort: Int get() = boundPort ?: port

    /** Suspend since 2026-09-23, for the one read below: Ktor 3 publishes the bound port only
     *  through the engine's suspend resolvedConnectors(). */
    public suspend fun start() {
        mgmtKey.get() // mint eagerly BEFORE the port opens — a dashboard load must not race it
        val engine = controlEngine()
        engine.start(wait = false)
        // Netty's start binds with bind(...).sync() and completes the resolved connectors before it
        // returns (read from the 3.5.2 bytecode), so this never actually waits.
        boundPort = engine.engine.resolvedConnectors().single().port
        server = engine
        mcpHost?.start()
    }

    /** The engine and the whole route table it serves. Extracted from [start] (V4-136): the route
     *  table has grown a row at a time and finally tripped the method-length wall, which was
     *  measuring the TABLE rather than the startup sequence — two different jobs that never belonged
     *  in one body. Adding a route should not be a reason to restructure startup, or the reverse. */
    private fun controlEngine(): EmbeddedServer<NettyApplicationEngine, *> =
        embeddedServer(Netty, port = port, host = "127.0.0.1") {
            guard.refuseForeignHosts(this)
            routing {
                fleet.register(this)
                lifecycle.register(this)
                configuration.register(this)
                usage.register(this)
                accounts.register(this)
                turns.register(this)
                sessionMount.register(this)
                events.register(this)
                diagnostics.register(this)
                models.register(this)
                launch.register(this)
                mcp?.register(this)
            }
        }

    @Synchronized
    public fun stop() {
        mcpHost?.stop()
        server?.stop(STOP_GRACE_MS, STOP_TIMEOUT_MS)
        server = null
        boundPort = null
    }
}
