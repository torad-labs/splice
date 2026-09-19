// PORT-OF: splice/app/Daemon.kt (start()'s ControlServer construction + bind-failure guard) @
// ed5c868 — invariants unchanged: the 14-argument ControlServer construction, the LaunchService
// materializer it takes, and the boundary-guarded srv.start() bind. Returns the started
// ControlServer, or null when the bind failed (another process owns the control port) — the
// caller's responsibility is to stop what it already started and exit cleanly in that case.
package splice.app

import kotlinx.coroutines.cancel
import splice.app.launch.HookProcessExec
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.control.ControlServer
import splice.control.DashboardPage
import splice.control.FailedHeads
import splice.control.LaunchService
import splice.control.ManagedHead
import splice.control.ShutdownDaemon
import splice.control.TurnPathStalled
import splice.control.mcp.McpHost
import splice.control.mcp.McpHostConfig
import splice.core.compaction.CompactionInstructions
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.launch.McpAccessKey
import splice.core.launch.McpSharing
import splice.core.prompt.SlotInstructions
import splice.core.sessions.HeadOfPid
import splice.core.sessions.ProcessEnvironment
import splice.core.sessions.SessionRegistry
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.spi.LifecycleScope
import splice.spi.ProcessDispatchers
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
    refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh()::refresh),
    /** v0.4.0 shared MCP hosting knobs ([daemon] mcp_hosting / mcp_hosting_exclude). */
    private val mcpHosting: McpHostingSettings = McpHostingSettings(),
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    /** V4-136: the daemon's ONE compaction resolver, handed on to the control server so
     *  /api/compaction/instructions reports the resolver the daemon actually compacts with. */
    private val compactionInstructions: CompactionInstructions = CompactionInstructions(),
) {
    private val boundary = DaemonBoundary()
    private val environment = ProcessEnvironment()

    // Held here so Daemon can drop the app.provider + spi imports (concentration, 2026-08-19).
    // probeScope is the daemon's OWN scope — ProviderAssembly must receive the SAME instance
    // stop() cancels; constructing a second one would leak prefetch coroutines.
    internal val signInPlanner = SignInPlanner()
    internal val buildInputs = HeadBuildInputs(config, signInPlanner)
    internal val probeScope = LifecycleScope(ProcessDispatchers().background())
    internal val providerAssembly = ProviderAssembly(statePaths, probeScope, log, refreshCall)

    /** V4-131: the daemon's ONE team store: the routes edit it and every head's slot resolver reads it. */
    internal val teams = ConsoleWiring.teamStore(statePaths)

    /** V4-134: the daemon's ONE console event bus and the publisher every head reports through. Held
     *  here, like [probeScope], because both sides of it hang off this class: Daemon hands [console]
     *  to HeadServerFactory before any head exists, and [start] hands its bus to the ControlServer. */
    internal val console = ConsoleEventPublisher(
        ConsoleWiring.activityStores(statePaths, config),
        slots = SlotInstructions(teams, ConsoleWiring.sessionAddress(statePaths)),
    )

    internal fun cancelProbes() {
        probeScope.cancel()
    }

    /** Constructs and binds the control plane. Returns null (having already called
     *  [shutdownDaemon] and logged) when the bind fails — defense in depth for the
     *  restart-into-a-still-bound-port race (BS-4 DEFECT B): unlike a per-head start, an uncaught
     *  EADDRINUSE here (a prior daemon that freed the lock but not yet the control port) would
     *  crash the new daemon to /dev/null, leaving zero serving. Exit cleanly instead — Main's
     *  finally stops the heads we started and releases the lock. */
    internal fun start(
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
        val mcpHost = McpHost(sharing, McpGlobalRead(home, log), config = mcpHostConfig(), log = log)
        val srv = ControlServer(
            controlPort,
            heads,
            config,
            mgmtKey,
            dashboardHtml,
            log,
            LaunchService(DaemonMaterializer.build(home, sharing.rewrite(), hookExec = HookProcessExec.exec)),
            shutdownDaemon,
            failedHeads,
            headCount,
            topologyDigest = topology.digest,
            configPath = topology.path?.toString().orEmpty(),
            topologyStale = TopologyLoader.staleProbe(topology.path, topology.digest),
            turnPathStalled = turnPathStalled,
            mcpHost = mcpHost,
            sessions = SessionRegistry(
                home.resolve(".claude").resolve("sessions"),
                HeadOfPid { pid -> environment.spliceHeadPort(pid)?.let { port -> headOfPort(heads, port) } },
            ),
            clientVersions = clientVersions,
        )
        // V4-136: assigned HERE, immediately after construction, because a constructor parameter
        // would widen ControlServer to 18 and the width ratchet forbids it. The compiler therefore
        // cannot check this line, which is exactly why a pin exists: removing it must fail a test,
        // not just leave the route answering its named 5xx in production.
        srv.compaction = compactionInstructions
        // V4-127/V4-137: the console's four ports, assigned for the same reason [compaction] is and
        // carrying the same hazard. They live in ConsoleWiring.kt (V4-156, concentration); this call
        // is pinned by ConsoleWiringPinTest, because deleting it would unwire all four at once.
        ConsoleWiring.wire(srv, topology)
        // V4-134: THE SAME bus the heads publish to. ControlServer has no bus of its own and answers
        // /api/events with a named 503 until this line runs; OneEventBusPinTest fails if the route's
        // bus and a head's are ever different instances.
        srv.events = console.bus
        // V4-130: the SAME stores the heads write through [console], read by the sessions routes.
        srv.activity = console.stores
        // V4-131: the SAME team store the heads' slot resolver reads, so an edit applies on the next turn.
        srv.teams = teams
        val controlBound = boundary.runCatchingDaemonBoundary { srv.start() }
            .onFailure {
                // SAFE-RENDER-EXEMPT[2026-08-31]: srv.start() bind failure — a SocketException names a port and an address, never file bytes
                log("[daemon] control plane could not bind :$controlPort (${it.message}); another owns it, exiting\n")
                shutdownDaemon()
            }
            .isSuccess
        return if (controlBound) srv else null
    }

    /** The head whose Claude Code wrapper listens on [port] — the launcher's ANTHROPIC_BASE_URL. */
    private fun headOfPort(heads: Map<String, ManagedHead>, port: Int): String? =
        heads.entries.firstOrNull { it.value.launchSpec?.port == port }?.key

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
        )
    }
}
