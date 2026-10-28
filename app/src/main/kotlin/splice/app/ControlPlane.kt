// PORT-OF: splice/app/Daemon.kt (start()'s ControlServer construction + bind-failure guard) @
// ed5c868 — invariants unchanged: the 14-argument ControlServer construction, the LaunchService
// materializer it takes, and the boundary-guarded srv.start() bind. Returns the started
// ControlServer, or null when the bind failed (another process owns the control port) — the
// caller's responsibility is to stop what it already started and exit cleanly in that case.
// 2026-10-13: the MCP host wiring left for ControlMcpWiring.kt, the LaunchService for ControlLaunchWiring.kt, the
// session source and driver for ControlSessions.kt, the readings and /health body for ControlHealth.kt, the guard and
// restart successor for ControlServerParts.kt, the console stores for ControlStores.kt and the plugin-flag checks for
// ControlPluginFlags.kt, each by its own seam with its state and its order unchanged (concentration, splice-lead).
package splice.app

import kotlinx.coroutines.cancel
import splice.app.auth.SignInPlanner
import splice.app.auth.claude.ClaudeAccountsArm
import splice.app.auth.claude.ClaudeAccountsSource
import splice.app.auth.claude.LoginJoined
import splice.app.control.ControlOwnership
import splice.app.control.ControlReadings
import splice.app.control.ControlRuntime
import splice.app.control.ControlServer
import splice.app.control.FailedHeads
import splice.app.control.KeyRotation
import splice.app.control.ManagedHead
import splice.app.control.ProcessReads
import splice.app.daemon.BootedTopology
import splice.app.head.HeadProbeReadings
import splice.app.peer.PeersWiring
import splice.app.provider.ClaudeAccountWiring
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ModelRosters
import splice.app.provider.ProviderAssembly
import splice.core.compaction.CompactionInstructions
import splice.core.usage.QuotaReadFailures
import splice.core.version.ClientVersionTracker
import splice.head.HeadDeps
import splice.launch.RemoteCommands
import splice.lifecycle.restart.ShutdownDaemon
import splice.oauth.codex.CodexRefresh
import splice.upstream.LifecycleScope
import splice.upstream.codemode.ProcessDispatchers
import java.nio.file.Path

internal class ControlPlane(
    /** The daemon's shared environment: its state, its settings, its management key and its log sink. */
    private val daemon: DaemonEnvironment,
    private val shutdownDaemon: ShutdownDaemon,
    /** JW-04 + V4-127: the booted config's identity (sha-256 of the parsed bytes, the resolved
     *  path) and what it declared, as one value. These were three separate parameters until
     *  2026-09-18, when the second of two unrelated rows took this constructor to 13 against the
     *  width ratchet's max of 12 — see BootedTopology.kt for why these three and not some other
     *  three. Still never the Topology object itself: only Daemon holds that. */
    private val topology: BootedTopology = BootedTopology(),
    refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh(daemon.log)::refresh),
    /** v0.4.0 shared MCP hosting knobs ([daemon] mcp_hosting / mcp_hosting_exclude). */
    private val mcpHosting: McpHostingSettings = McpHostingSettings(),
    private val clientVersions: ClientVersionTracker = ClientVersionTracker(),
    /** V4-136: the daemon's ONE compaction resolver, handed on to the control server so
     *  /api/compaction/instructions reports the resolver the daemon actually compacts with. */
    private val compactionInstructions: CompactionInstructions = CompactionInstructions(),
    /** The control listener a socket manager handed this process, when it did. */
) {
    // Views over [daemon], never copies: a stored copy would fix the value at construction.
    private val statePaths get() = daemon.statePaths
    private val config get() = daemon.config
    private val log get() = daemon.log
    private val boundary = DaemonBoundary()
    private val sessionWiring = ControlSessions(daemon)
    private val parts = ControlServerParts(daemon, topology)
    private val claudeLogin = ClaudeLoginSlot()

    /** LAN share: the commands this machine runs on another's heads, assigned by Daemon before [start] (the launch
     *  service asks them for a name no head here has). Null runs none. */
    internal var remoteCommands: RemoteCommands? = null

    /** Captures the owner only at send time, after launch composition has wired the live native places. */
    internal val credentialAccountNames = HeadDeps.CredentialAccountNames { key ->
        // The row records the account's ref, never its email or id (labels only, 2026-10-12).
        claudeLogin.owner?.accountForCredential(key)?.ref
    }

    /** Each head's sent credential digest, read by the same late-wired owner: which login carries that head, and
     *  which carries each session the request named. */
    internal val sentCredentials = HeadSentCredentials { head, session, key ->
        claudeLogin.owner?.sent(head, session, key)
    }

    /** The MCP host wiring: its planner and host, and the census, sharing, attached sessions and hub verdict it
     *  keeps. */
    private val mcp = ControlMcpWiring(daemon, mcpHosting)
    private val health = ControlHealth(topology, clientVersions, mcp)

    /** 2026-09-22: every head's DISCOVERED models — Daemon.start() resolves them before any head is
     *  assembled, and each head's catalog reads its own through [buildInputs]. */
    internal val modelRosters = ModelRosters(statePaths, log)

    // Held here so Daemon can drop the app.provider + spi imports (concentration, 2026-08-19).
    // probeScope is the daemon's OWN scope — ProviderAssembly must receive the SAME instance
    // stop() cancels; constructing a second one would leak prefetch coroutines.
    internal val signInPlanner = SignInPlanner()
    internal val buildInputs = HeadBuildInputs(config, signInPlanner, modelRosters)
    internal val probeScope = LifecycleScope(ProcessDispatchers().background())

    /** MCP hub step 6: the --plugin-dir-no-mcp verdicts and their check (ControlPluginFlags). */
    private val pluginFlags = ControlPluginFlags(daemon, probeScope)
    internal val pluginFlagChecks = pluginFlags.checks

    /** The daemon's one record of failed limits reads: each login's poller writes it, /api/accounts reads it. */
    internal val quotaReadFailures = QuotaReadFailures()

    /** The control server's owner from construction to the stop: see [ControlOwnership]. */
    internal val ownership = ControlOwnership(boundary, log, shutdownDaemon)
    private val claudeIdentities = splice.app.auth.claude.ClaudeIdentityRefresh(
        probeScope,
        ProcessDispatchers().io(),
        splice.app.auth.claude.ClaudeCredentialProfiles(statePaths.stateDir, log),
        splice.app.auth.claude.ClaudeProfileProbe(
            splice.usage.quota.ClientUserAgent(clientVersions::newestClaudeCodeUserAgent),
            log,
        ),
        log,
    )
    internal val providerAssembly = ProviderAssembly(
        statePaths,
        probeScope,
        log,
        refreshCall,
        claudeAccounts = ClaudeAccountWiring(
            statePaths,
            log,
            identities = claudeIdentities,
            nativePlaces = splice.accounts.claude.ClaudeLoginPlacesSource { claudeLogin.owner },
        ),
    )

    /** The console's stores and its one publisher (ControlStores): Daemon hands [console] to HeadServerFactory before
     *  any head exists, and [start] hands the stores to the ControlServer. */
    private val stores = ControlStores(daemon, probeScope)
    internal val teams = stores.teams
    internal val budgets = stores.budgets
    internal val alerts = stores.alerts
    internal val perfRows = stores.perfRows
    internal val foregroundTools = stores.foregroundTools
    internal val playgroundProviders = stores.playgroundProviders
    internal val playground = stores.playground
    internal val console = stores.console

    /** The daemon's one LaunchService, built at [start]: it fills [claudeLogin] as it wires the Claude login arm. */
    private val launch = ControlLaunchWiring(
        daemon,
        topology,
        pluginFlags.holds,
        ClaudeLoginInputs(
            probeScope,
            splice.usage.quota.ClientUserAgent(clientVersions::newestClaudeCodeUserAgent),
            claudeIdentities,
            providerAssembly.claudePoolChanges,
            claudeLogin,
        ),
    )

    internal fun cancelProbes() {
        probeScope.cancel()
    }

    /** The sockets a manager handed the daemon, assigned by Daemon before [start] (see [AdoptedServing]). The
     *  default binds the control port itself, which is every start without a socket manager. */
    internal var adopted: AdoptedServing = AdoptedServing()

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
        probes: HeadProbeReadings,
    ): ControlServer? {
        // Knob.DEBUG is the daemon-wide verbose-logging switch (env CLAUDEX_DEBUG / CODEX_PROXY_DEBUG):
        // when it is on, the daemon marks its own log so the extra verbosity can be told apart. This
        // is the one production read of the knob — the accessor had no consumer before V4-110.
        if (config.getConfig().debug) log("[daemon] debug logging enabled\n")
        val home = statePaths.rootDir.parent ?: statePaths.rootDir
        val sharing = mcp.planner(controlPort)
        val mcpHost = mcp.host(home, sharing, heads.values.mapNotNull { it.launchSpec })
        val mcpServices = mcp.services(mcpHost, home)
        // One session source serves the sessions routes and the heads route's client pids.
        val sessions = sessionWiring.source(home, heads, console, foregroundTools)
        val signals = health.signals(heads, sessions, probes)
        val guard = parts.guard()
        val srv = ControlServer(
            adopted.control(controlPort),
            heads,
            config,
            guard,
            log,
            readings = ControlReadings(health.report(heads, failedHeads, headCount, probes, signals), signals),
            runtime = ControlRuntime(
                launchService = launch.service(home, sharing, controlPort, mcp.hubLive)
                    .also { it.remotes = remoteCommands },
                shutdownDaemon = shutdownDaemon,
                sessions = sessions,
                mcpHost = mcpHost,
                mcpServices = mcpServices,
                clientVersions = clientVersions,
            ),
        )
        wireConsolePorts(srv)
        // Oct 19, 2026: the peer helpers and their journals, over the same store the guard admits peer keys from.
        srv.peerServices = PeersWiring().services(guard.peers, statePaths.stateDir, probeScope, log)
        parts.wireRestartSuccessor(srv, controlPort)
        return if (ownership.bind(srv, controlPort)) srv.also { mcpServices.refresh() } else null
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
     *  (ConsoleWiringPinTest). V4-130/V4-131: the SAME stores/team store the heads already write through. V4-133:
     *  [ConsoleWiring.wireV4133] carries the same hazard for the budget/alert stores and the
     *  playground probe, and V4-239's [ConsoleWiring.wireVerbReads] for the models, trace and wire reads. */

    private fun wireConsolePorts(srv: ControlServer) {
        srv.ports.turns.codeMode = providerAssembly.codeModeControls
        srv.ports.turns.foreground = foregroundTools
        srv.ports.compaction = compactionInstructions
        srv.ports.claudeLogins = claudeLogin.owner
        srv.ports.quotaReadFailures = quotaReadFailures
        srv.ports.lifecycle.keyRotation = KeyRotation(daemon.mgmtKey, daemon.mcpAccess, statePaths, log)
        providerAssembly.claudePoolChanges.onJoined = LoginJoined { head -> srv.sessionWake?.joined(head) }
        providerAssembly.claudePoolChanges.watch(probeScope, log)
        srv.ports.budgetSpending = console.budgets
        srv.ports.mcpInventory = mcp.inventory
        srv.ports.mcpSharing = mcp.sharing
        srv.ports.mcpAttached = mcp.attached
        ConsoleWiring.wire(srv, topology, modelRosters, statePaths.configBackupsDir, log)
        srv.ports.activity = console.stores
        srv.ports.teams = teams
        srv.ports.sessionDriver = sessionWiring.driver()
        srv.ports.processes = ProcessReads { sessionWiring.processes() }
        ConsoleWiring.wireV4133(srv, budgets, alerts, playground)
        ConsoleWiring.wireVerbReads(srv, topology, statePaths, console)
        // A Claude head's add and remove go through folders splice owns, which the generic accounts port refuses
        // by kind. Wrapped AFTER ConsoleWiring.wire assigns that port, and reading the arm per call, so a daemon
        // with no Claude head wraps a port that simply never answers "client".
        srv.ports.accounts = srv.ports.accounts?.let { generic ->
            ClaudeAccountsArm(generic, ClaudeAccountsSource { claudeLogin.accounts })
        }
    }

    /** Pin the environment override to the live state directory, not its parent: the wrong parent
     *  mints a second management key and strands the daemon's prior history after restart. */
    internal fun successorStateDir(): Path = parts.successorStateDir()
}
