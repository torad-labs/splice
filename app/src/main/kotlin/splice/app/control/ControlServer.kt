// PORT-OF: server/src/control/api.mjs + control-server.mjs @ pre-public-port-baseline — the centralized control
// plane (spliced, loopback :3096). Bearer-guarded /api/* aggregating every head, and the console's pages at /
// (removed Oct 7, 2026, back Oct 10 as the drawing on live data, ConsoleMount). Single-daemon
// simplification (plan): heads are IN-PROCESS Head
// objects, so lifecycle is start()/stop() calls and config is ONE shared service — NO PATCH
// fanout (deleted, not ported). File-based truth (auth/usage/compact/logs) so a DOWN head still
// shows last-known state.
//
// HD-24: split into splice.app.control.api (the HTTP surface — payload projections and by-name
// routes) + splice.app.control (this file: ctor/routing/lifecycle, plus ManagedHead/ControlPorts and
// the adapters that project ManagedHead into each feature's contract). One direction of real
// dependency: api -> domain.
//
// LAYOUT-01: each capability owns one MOUNT in splice.app.control.mount or splice.app.control.usage.
// Each mount owns its feature's route objects and registers its rows behind the one ControlGuard. This file keeps
// construction, lifecycle and the order the mounts register in. The table had reached 68 rows importing
// 37 slice packages, which made this one file every feature's edit. Nov 21, 2026: a capability that needs only the
// ports and the guard is built in StandaloneMounts, beside the mount shape, for the same reason.
package splice.app.control

import io.ktor.server.application.pluginOrNull
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import kotlinx.coroutines.CancellationException
import splice.app.control.api.ControlAudit
import splice.app.control.api.ControlHealthReport
import splice.app.control.api.HeadResolver
import splice.app.control.api.HeadSignals
import splice.app.control.mount.AccountsMount
import splice.app.control.mount.AddMount
import splice.app.control.mount.BoundResource
import splice.app.control.mount.ConfigurationMount
import splice.app.control.mount.ConsoleMount
import splice.app.control.mount.ControlGuard
import splice.app.control.mount.ControlMount
import splice.app.control.mount.DaemonSelfAnswers
import splice.app.control.mount.DiagnosticsMount
import splice.app.control.mount.FleetMount
import splice.app.control.mount.LaunchMount
import splice.app.control.mount.LaunchSessions
import splice.app.control.mount.LifecycleMount
import splice.app.control.mount.ManagerMount
import splice.app.control.mount.McpMount
import splice.app.control.mount.ModelsMount
import splice.app.control.mount.ProjectsMount
import splice.app.control.mount.SessionsMount
import splice.app.control.mount.SessionsWiring
import splice.app.control.mount.StandaloneMounts
import splice.app.control.mount.TeamsMount
import splice.app.control.mount.TraceMount
import splice.app.control.mount.TurnsMount
import splice.app.control.peer.PeerServices
import splice.app.control.peer.PeersMount
import splice.app.control.usage.UsageMount
import splice.app.head.HeadRemovalAssembly
import splice.app.peer.PeersWiring
import splice.app.retire.HeadRetirementAssembly
import splice.app.retire.RetirementMount
import splice.core.config.ConfigService
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.diagnostics.logs.LogFileSource
import splice.launch.recipe.LaunchService
import splice.lifecycle.restart.DaemonSuccessor
import splice.lifecycle.restart.ShutdownDaemon
import splice.sessions.registry.SessionSource

/** What a control server reports about its heads: the /health body and the per-head signals (a silent runtime, a
 *  quota reset, a full window) the heads route and the doctor read. */
internal class ControlReadings(val health: ControlHealthReport, private val signals: HeadSignals) {
    fun resolver(heads: Map<String, ManagedHead>): HeadResolver = HeadResolver(heads, signals)
}

internal class ControlServer(
    /** The port to serve on, and the socket a manager already holds for it (see [ControlListen]). */
    private val listen: ControlListen,
    private val heads: Map<String, ManagedHead>,
    private val config: ConfigService,
    /** The door every route runs behind, and the request admission, both built by ControlPlane. */
    private val guard: ControlGuard,
    private val log: LogSink,
    /** The /health body served on the liveness row, read per request, and the per-head readings the heads route
     *  and the doctor read. */
    private val readings: ControlReadings,
    /** The runtime collaborators the mounts share: launch, shutdown, the session registry, shared MCP
     *  hosting (null keeps the control plane exactly as before) and the client version tracker. */
    private val runtime: ControlRuntime = ControlRuntime(),
) {
    // Views over [runtime], never copies: a stored copy would fix the value at construction.
    private val launchService: LaunchService? get() = runtime.launchService
    private val shutdownDaemon: ShutdownDaemon get() = runtime.shutdownDaemon
    private val sessions: SessionSource? get() = runtime.sessions
    private val clientVersions: ClientVersionTracker get() = runtime.clientVersions

    /** The nine ports ControlPlane wires after construction — see [ConsolePorts], which carries the
     *  discipline they share and why they left this file (V4-161). Read at CALL time, never captured. */
    public val ports: ConsolePorts = ConsolePorts()

    internal fun wirePerformance(performance: splice.app.observability.PerfTelemetry) {
        projects.requestTags?.let { performance.inspection.projects = it }
        performance.accounts(accounts.performanceNames())
    }

    /** The roster /api/accounts serves, for the daemon's own reading of it (LoginAlerting). */
    internal fun accountRoster(): AccountRoster = AccountRoster { accounts.accountsJson() }

    private val resolver = readings.resolver(heads)
    private val audit = ControlAudit(log)

    // One mount per capability. Every mount reads [ports] at CALL time, never at construction:
    // ControlPlane assigns them after this server exists, so a captured port would be null forever.
    private val fleet = FleetMount(
        heads,
        resolver,
        audit,
        guard,
        ports,
        LogFileSource(config.statePaths.logsDir.resolve("daemon.log")),
    )
    private val lifecycle = LifecycleMount(shutdownDaemon, ports, guard, heads, log)

    /** The control plane arms the raw successor before binding; both Restart and add-save share it. */
    public fun wireRestartSuccessor(successor: DaemonSuccessor) {
        lifecycle.restarts.wireSuccessor(successor)
    }

    // V4-220 item 3: the add's save restarts through lifecycle's own restarts, never a second path.
    private val add = AddMount(ports, guard, lifecycle.restarts, shutdownDaemon, log)
    private val removal = HeadRemovalAssembly(config, ports, runtime, heads, log).build()

    // Nov 19, 2026: `splice claude retire`, `restore` and `check` (head-catalog), beside the removal.
    private val retirement = RetirementMount(HeadRetirementAssembly(config, ports, runtime, heads, log).build(), guard)
    private val configuration = ConfigurationMount(config, readings.health.staleness(), ports, guard, removal)
    private val usage = UsageMount(heads, resolver, config, clientVersions, ports, guard, log)
    private val accounts = AccountsMount(heads, resolver, ports, guard, log, config.statePaths.stateDir, add.restart)
    private val turns = TurnsMount(heads, resolver, config, ports, guard)
    private val trace = TraceMount(resolver, config, ports, guard)
    private val sessionWiring = SessionsWiring(sessions, heads, config, ports, log)

    /** What a login added after a plan limit does for the sessions it stopped (ControlPlane hooks the pool to it). */
    internal val sessionWake: SessionWake? get() = sessionWiring.sessionWake
    private val sessionMount = SessionsMount(sessions, sessionWiring, heads, config, guard, ports, launchService)
    private val teams = TeamsMount(sessionWiring, sessions, heads, ports, guard)
    private val projects = ProjectsMount(sessionWiring, sessions, config, ports, guard)

    // Oct 15, 2026: the splice manager the composer opens, started and moved through the same drive (ManagerMount).
    private val managerMount = ManagerMount(sessionWiring, sessions, heads, resolver, config, guard, usage.lines)

    // Every request row names its project through the one resolver the session rows use.
    init {
        projects.requestTags?.let(usage::tagProjects)
        // Requests and Usage name a login by the label Accounts shows, never by the email a row recorded.
        usage.nameAccounts(accounts.recordedNames())
    }

    // V4-230: the daemon's doctor reads these three answers in process; over loopback, from inside the
    // request it was serving, it read its own /health as "stopped" in 3 page loads of 6.
    private val diagnostics = DiagnosticsMount(
        resolver,
        ports,
        guard,
        log,
        DaemonSelfAnswers(heads, config, readings.health, fleet, accounts),
    )

    private val models = ModelsMount(heads, ports, guard)
    private val launchSessions = LaunchSessions(sessions, ports, config.statePaths)
    private val launch = LaunchMount(heads, resolver, launchService, audit, log, guard, launchSessions)

    // Oct 10, 2026: the console's pages at /, open rows that carry no data (ConsoleMount). Oct 26, 2026: and its
    // sign-in, a single-use code traded for the console key, so no address carries a key.
    private val console = ConsoleMount(guard)

    // Oct 19, 2026: named peers (docs/specs/peer-messaging.md 3), over the store the guard admits peer keys from.
    private val peers: PeersMount? = sessions?.let { source ->
        guard.peers?.let { store -> PeersWiring().mount(store, source, guard, log) { peerServices } }
    }

    /** The helper supervisor and the journal behind the peer routes, which ControlPlane sets once it has started them;
     *  read at each request, and empty (503) until then. */
    @Volatile
    internal var peerServices: PeerServices = PeerServices()

    // Nov 16, 2026: Claude Desktop's Code tab (docs/specs/desktop-door.md): its door and `splice desktop`.
    private val desktop = DesktopRoutes(ports, guard, ClaudeLink { launchService?.wrap })

    // Nov 21, 2026: the capabilities that need only the ports and the guard (the console's pilot, the modes, a
    // server's keys and budgets) are built beside the mount shape, so adding one is not an edit of this file.
    private val standalone = StandaloneMounts(ports, guard)

    // The order the rows register in: MCP last, and only when the daemon hosts MCP at all (null keeps the control
    // plane exactly as before). The MCP host is the one bound resource: the server starts it after the bind.
    private val mcp: McpMount? = runtime.mcpHost?.let { host ->
        McpMount(
            host,
            guard,
            McpLaunchFolders(sessions, McpServerClasses { host.sharing.plan(it) }),
            runtime.mcpServices,
        )
    }
    private val mounts: List<ControlMount> = listOf(
        ControlMount(fleet::register),
        ControlMount(lifecycle::register),
        ControlMount(add::register),
        ControlMount(configuration::register),
        ControlMount(usage::register),
        ControlMount(accounts::register),
        ControlMount(turns::register),
        ControlMount(trace::register),
        ControlMount(sessionMount::register),
        ControlMount(teams::register),
        ControlMount(projects::register),
        ControlMount(managerMount::register),
        ControlMount(diagnostics::register),
        ControlMount(models::register),
        ControlMount(launch::register),
    ) + standalone.rows +
        listOf(ControlMount(desktop::register), ControlMount(retirement::register), ControlMount(console::register)) +
        listOfNotNull(peers?.let { ControlMount(it::register) }, mcp) + standalone.last
    private val resources: List<BoundResource> = listOfNotNull(mcp, sessionWake)

    private val engineShell = ControlEngine(listen, guard, readings, mounts, runtime.callDispatcher)

    @Volatile
    private var server: EmbeddedServer<NettyApplicationEngine, *>? = null

    /** Set by the first [stop] and never cleared: a server stopped at any point, before its bind included, refuses to bind. */
    private var stopRequested = false
    private var resourcesStopped = false

    /** What the connector actually bound in the current [start]; null while stopped. */
    @Volatile
    private var boundPort: Int? = null

    /** Whether the listener is up, from the moment the bound engine is published until the stop. A start that failed
     *  while this is true failed after the bind, and a port conflict is a failure while it is false. */
    internal val bound: Boolean get() = boundPort != null

    /** The port this control plane listens on: the one its connector BOUND while running — the
     *  OS-assigned one when it was constructed with port 0 — and the configured [ControlListen.port] otherwise.
     *  A caller that wants a free port passes 0 and reads this after [start]: leasing a number with
     *  ServerSocket(0) and handing it here to bind later leaves a window in which anything else may
     *  take it (the BindException class of CI run 35881955038). */
    public val listeningPort: Int get() = boundPort ?: listen.port

    /** v0.4.0 review: every route the running engine serves, read off its router, so the test that walks
     *  every door takes the list from here and never from a list kept beside it (a route added with the
     *  wrong door would otherwise pass). Empty while stopped. */
    internal fun routeTable(): List<RoutingNode> =
        server?.application?.pluginOrNull(RoutingRoot)?.getAllRoutes().orEmpty()

    /** Suspend since 2026-09-23, for the one read below: Ktor 3 publishes the bound port only
     *  through the engine's suspend resolvedConnectors(). */
    public suspend fun start() {
        guard.mintKey() // mint eagerly BEFORE the port opens — a dashboard load must not race it
        val engine = engineShell.open()
        refuseWhenStopped()
        engine.start(wait = false)
        // Netty's start binds with bind(...).sync() and completes the resolved connectors before it
        // returns (read from the 3.5.2 bytecode), so this never actually waits.
        val port = engine.engine.resolvedConnectors().single().port
        publish(engine, port)
        // under the monitor [stop] takes: a stop that ran between the publish and here already stopped resources that
        // had not started, so starting them now would leave them running in a daemon that is stopping
        synchronized(this) {
            if (stopRequested) throw CancellationException("the control server was stopped before its resources ran")
            resources.forEach { it.start() }
        }
    }

    private fun refuseWhenStopped() {
        if (synchronized(this) { stopRequested }) {
            throw CancellationException("the control server was stopped before it bound")
        }
    }

    /** The engine is bound; it becomes this server's own, which [stop] closes, unless a [stop] already ran, before or while it
     *  bound. That stop found no engine to close, so the bound listener is closed here and the start ends
     *  cancelled, because a listener nobody owns would outlive the daemon that opened it. */
    private fun publish(engine: EmbeddedServer<NettyApplicationEngine, *>, port: Int) {
        val stoppedWhileBinding = synchronized(this) {
            if (!stopRequested) {
                boundPort = port
                server = engine
            }
            stopRequested
        }
        if (stoppedWhileBinding) {
            engineShell.stop(engine)
            throw CancellationException("the control server was stopped while it was binding")
        }
    }

    /** #525: stops what rides on the listener and leaves the listener up. A hand-over calls it before the heads stop
     *  taking turns, because a hosted MCP child takes up to two seconds to die and that wait used to fall between the
     *  heads closing admission and the next daemon serving. [stop] then finds the resources stopped. */
    @Synchronized
    public fun quiet() {
        stopRequested = true
        stopResources()
    }

    @Synchronized
    public fun stop() {
        stopRequested = true
        stopResources()
        engineShell.stop(server)
        server = null
        boundPort = null
    }

    /** Once, under this server's monitor: [quiet] may have stopped them before [stop]. */
    private fun stopResources() {
        if (resourcesStopped) return
        resourcesStopped = true
        resources.forEach { it.stop() }
    }
}
