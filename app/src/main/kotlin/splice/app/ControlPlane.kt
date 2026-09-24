// PORT-OF: splice/app/Daemon.kt (start()'s ControlServer construction + bind-failure guard) @
// ed5c868 — invariants unchanged: the 14-argument ControlServer construction, the LaunchService
// materializer it takes, and the boundary-guarded srv.start() bind. Returns the started
// ControlServer, or null when the bind failed (another process owns the control port) — the
// caller's responsibility is to stop what it already started and exit cleanly in that case.
package splice.app

import kotlinx.coroutines.cancel
import splice.app.auth.SignInPlanner
import splice.app.control.ControlServer
import splice.app.control.DashboardPage
import splice.app.control.FailedHeads
import splice.app.control.ManagedHead
import splice.app.control.TopologyDigest
import splice.app.control.TurnPathStalled
import splice.app.daemon.BootedTopology
import splice.app.daemon.DaemonMaterializer
import splice.app.daemon.TopologyStaleness
import splice.app.launch.HookProcessExec
import splice.app.probe.UpstreamPlaygroundProbe
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderAssembly
import splice.client.ClaudeConfigMaterializer
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpSharing
import splice.client.resume.AuthHeaderFile
import splice.client.resume.ResumeHookTarget
import splice.configuration.topology.TopologyStale
import splice.control.mcp.APP_MCP_SLICE
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.core.compaction.CompactionInstructions
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.launch.LaunchSpec
import splice.launch.recipe.LaunchService
import splice.lifecycle.restart.ShutdownDaemon
import splice.oauth.codex.CodexRefresh
import splice.sessions.prompt.SlotInstructions
import splice.sessions.registry.ProcessEnvironment
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

internal class ControlPlane(
    private val statePaths: StatePaths,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: DashboardPage,
    private val log: LogSink,
    private val shutdownDaemon: ShutdownDaemon,
    /** JW-04 + V4-127: the booted config's identity (sha-256 of the parsed bytes, the resolved
     *  path) and what it declared, as one value. These were three separate parameters until
     *  2026-09-18, when the second of two unrelated rows took this constructor to 13 against the
     *  width ratchet's max of 12 — see BootedTopology.kt for why these three and not some other
     *  three. Still never the Topology object itself: only Daemon holds that. */
    private val topology: BootedTopology = BootedTopology(),
    refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh(log)::refresh),
    /** v0.4.0 shared MCP hosting knobs ([daemon] mcp_hosting / mcp_hosting_exclude). */
    private val mcpHosting: McpHostingSettings = McpHostingSettings(),
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    /** V4-136: the daemon's ONE compaction resolver, handed on to the control server so
     *  /api/compaction/instructions reports the resolver the daemon actually compacts with. */
    private val compactionInstructions: CompactionInstructions = CompactionInstructions(),
) {
    private val boundary = DaemonBoundary()
    private val environment = ProcessEnvironment()

    /** 2026-09-22: every head's DISCOVERED models — Daemon.start() resolves them before any head is
     *  assembled, and each head's catalog reads its own through [buildInputs]. */
    internal val modelRosters = ModelRosters(statePaths, log)

    // Held here so Daemon can drop the app.provider + spi imports (concentration, 2026-08-19).
    // probeScope is the daemon's OWN scope — ProviderAssembly must receive the SAME instance
    // stop() cancels; constructing a second one would leak prefetch coroutines.
    internal val signInPlanner = SignInPlanner()
    internal val buildInputs = HeadBuildInputs(config, signInPlanner, modelRosters)
    internal val probeScope = LifecycleScope(ProcessDispatchers().background())
    internal val providerAssembly = ProviderAssembly(statePaths, probeScope, log, refreshCall)

    /** V4-131: the daemon's ONE team store: the routes edit it and every head's slot resolver reads it. */
    internal val teams = ConsoleWiring.teamStore(statePaths)

    /** V4-133 (FEATURES.md §5/§6): the daemon's ONE budget and alert-settings stores. */
    internal val budgets = ConsoleWiring.budgetStore(statePaths)
    internal val alerts = ConsoleWiring.alertStore(statePaths)

    /** V4-133: POST /api/playground's ONE upstream probe. Built from [topology]'s booted path (not
     *  the Topology object itself — see UpstreamPlaygroundProbe's header for why it re-parses fresh
     *  per call) so a null path (a daemon booted without a config file) degrades the same way
     *  [ConsoleWiring.wire]'s topology writer does. */
    internal val playground = UpstreamPlaygroundProbe(topology.path)

    /** V4-134: the daemon's ONE console event bus and the publisher every head reports through. Held
     *  here, like [probeScope], because both sides of it hang off this class: Daemon hands [console]
     *  to HeadServerFactory before any head exists, and [start] hands its bus to the ControlServer. */
    internal val console = ConsoleEventPublisher(
        ConsoleWiring.activityStores(statePaths, config),
        slots = SlotInstructions(teams, ConsoleWiring.sessionAddress(statePaths)),
    )

    /** The daemon's one materializer: the real hook exec (V4-103), and the control port the resume hook
     *  (V4-169) calls back on with the turn key's header file — handed over as its SOURCE, written
     *  current by each install, so start() does no file I/O here. Its own member so the start()
     *  budget holds and the call stays greppable. */
    private fun materializer(home: Path, sharing: McpSharing, controlPort: Int): ClaudeConfigMaterializer =
        DaemonMaterializer.build(
            home,
            sharing.rewrite(),
            hookExec = HookProcessExec.exec,
            resumeHook = ResumeHookTarget(controlPort, AuthHeaderFile(TurnKey(mgmtKey)::headerFile)),
        )

    internal fun cancelProbes() {
        probeScope.cancel()
    }

    /** Constructs and binds the control plane. Returns null (having already called
     *  [shutdownDaemon] and logged) when the bind fails — defense in depth for the
     *  restart-into-a-still-bound-port race (BS-4 DEFECT B): unlike a per-head start, an uncaught
     *  EADDRINUSE here (a prior daemon that freed the lock but not yet the control port) would
     *  crash the new daemon to /dev/null, leaving zero serving. Exit cleanly instead — Main's
     *  finally stops the heads we started and releases the lock.
     *
     *  A [controlPort] of 0 binds an OS-assigned port, read back from the returned server's
     *  [ControlServer.listeningPort]. The MCP endpoint prefix and the resume hook's port are built
     *  from the ARGUMENT before the bind, so 0 serves only callers that exercise neither — the
     *  wiring tests; the daemon always passes its configured port. */
    internal suspend fun start(
        controlPort: Int,
        heads: Map<String, ManagedHead>,
        failedHeads: FailedHeads,
        // Configured total so readyHeads + failedHeads == heads holds even when a head fails to
        // ASSEMBLE (it never enters `heads`) — review 2026-07-23.
        headCount: Int,
        turnPathStalled: TurnPathStalled,
    ): ControlServer? {
        // Knob.DEBUG is the daemon-wide verbose-logging switch (env CLAUDEX_DEBUG / CODEX_PROXY_DEBUG):
        // when it is on, the daemon marks its own log so the extra verbosity can be told apart. This
        // is the one production read of the knob — the accessor had no consumer before V4-110.
        if (config.getConfig().debug) {
            log("[daemon] debug logging enabled\n")
        }
        val home = statePaths.rootDir.parent ?: statePaths.rootDir
        val sharing = McpSharing(
            enabled = mcpHosting.enabled,
            exclude = mcpHosting.exclude,
            endpointPrefix = "http://127.0.0.1:$controlPort/mcp/",
            bearer = McpAccessKey(mgmtKey::get),
        )
        val mcpHost = mcpHost(home, sharing, heads.values.mapNotNull { it.launchSpec })
        // V4-162: the version the daemon RUNS, which a window-only edit moves (TopologyWindows). A
        // control plane built without one publishes the booted digest and compares bytes, as before.
        val running = topology.running
        val srv = ControlServer(
            controlPort,
            heads,
            config,
            mgmtKey,
            dashboardHtml,
            log,
            LaunchService(materializer(home, sharing, controlPort)),
            shutdownDaemon,
            failedHeads,
            headCount,
            topologyDigest = TopologyDigest { running?.digest() ?: topology.digest },
            configPath = topology.path?.toString().orEmpty(),
            topologyStale = running?.let { TopologyStale(it::stale) }
                ?: TopologyStaleness.probe(topology.path, topology.digest),
            turnPathStalled = turnPathStalled,
            mcpHost = mcpHost,
            sessions = SessionRegistry(
                home.resolve(".claude").resolve("sessions"),
                RouteOfPid { pid -> environment.route(pid) { port -> headOfPort(heads, port) } },
            ),
            clientVersions = clientVersions,
        )
        wireConsolePorts(srv)
        val controlBound = boundary.runCatchingDaemonBoundary { srv.start() }
            .onFailure {
                // SAFE-RENDER-EXEMPT[2026-08-31]: srv.start() bind failure — a SocketException names a port and an address, never file bytes
                log("[daemon] control plane could not bind :$controlPort (${it.message}); another owns it, exiting\n")
                shutdownDaemon()
            }
            .isSuccess
        return if (controlBound) srv else null
    }

    /** Every port [srv] takes after construction, extracted out of [start] (LongMethod — the same
     *  reason ControlServer's own route table split into [ControlServer.consoleRoutes]).
     *
     *  V4-136: [compactionInstructions] is assigned HERE, immediately after construction, because a
     *  constructor parameter would widen ControlServer to 18 and the width ratchet forbids it. The
     *  compiler therefore cannot check this line, which is exactly why a pin exists: removing it
     *  must fail a test, not just leave the route answering its named 5xx in production.
     *
     *  V4-127/V4-137: [ConsoleWiring.wire]'s four ports carry the same hazard and the same pin
     *  (ConsoleWiringPinTest). V4-134: [console].bus is THE SAME bus the heads publish to —
     *  OneEventBusPinTest fails if the route's bus and a head's are ever different instances. V4-130/
     *  V4-131: the SAME stores/team store the heads already write through. V4-133:
     *  [ConsoleWiring.wireV4133] carries the same hazard for the budget/alert stores and the
     *  playground probe. */
    private fun wireConsolePorts(srv: ControlServer) {
        srv.ports.compaction = compactionInstructions
        ConsoleWiring.wire(srv, topology, modelRosters)
        srv.ports.events = console.bus
        srv.ports.activity = console.stores
        srv.ports.teams = teams
        ConsoleWiring.wireV4133(srv, budgets, alerts, playground)
    }

    /** The head whose Claude Code wrapper listens on [port] — the launcher's ANTHROPIC_BASE_URL. */
    private fun headOfPort(heads: Map<String, ManagedHead>, port: Int): String? =
        heads.entries.firstOrNull { it.value.launchSpec?.port == port }?.key

    /** V4-146: one McpGlobalRead instance for both the rewrite pipeline and the census — a second
     *  one would double the "malformed"/"unreadable" diagnostics on the same file. Split out of
     *  [start] to keep that budget; the census only WIDENS what `/api/mcp` can report, it never
     *  changes what [sharing] rewrites (see McpHostingWiring.kt's header). */
    private fun mcpHost(home: Path, sharing: McpSharing, launchSpecs: List<LaunchSpec>): McpHost {
        val globalRead = McpGlobalRead(home, log)
        return McpHost(
            sharing,
            globalRead,
            config = mcpHostConfig(),
            log = log,
            inventory = McpInventoryWiring(home, sharing, globalRead, launchSpecs).inventory,
        )
    }

    /** V4-110: the shared MCP host's four lifecycle values, read from the knob layer (daemon-global).
     *  Absent knobs keep their declared defaults — the map is always seeded by the merge, so each
     *  read is a normalized Long. The McpHostConfig defaults are the same numbers, kept in one place
     *  (the Knob enum) rather than restated here. */
    private fun mcpHostConfig(): McpHostConfig {
        val m = config.getConfig().asMap()
        fun ms(knob: Knob): Long = (m[knob.key] as? Long) ?: (knob.default as Long)
        return McpHostConfig(
            idleTimeout = ms(Knob.MCP_IDLE_TIMEOUT_MS).milliseconds,
            maxServers = ms(Knob.MCP_MAX_SERVERS).toInt(),
            requestTimeout = ms(Knob.MCP_REQUEST_TIMEOUT_MS).milliseconds,
            initializeTimeout = ms(Knob.MCP_INITIALIZE_TIMEOUT_MS).milliseconds,
            // V4-176: the slice is a NAME, not a duration, and a blank one would spawn every hosted
            // child into a `--slice=` systemd rejects. An empty knob therefore falls back to the
            // declared default rather than being passed through.
            slice = (m[Knob.MCP_SLICE.key] as? String)?.takeIf { it.isNotBlank() } ?: APP_MCP_SLICE,
        )
    }
}
