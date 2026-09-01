// PORT-OF: splice/app/Daemon.kt (start()'s ControlServer construction + bind-failure guard) @
// ed5c868 — invariants unchanged: the 14-argument ControlServer construction, the LaunchService
// materializer it takes, and the boundary-guarded srv.start() bind. Returns the started
// ControlServer, or null when the bind failed (another process owns the control port) — the
// caller's responsibility is to stop what it already started and exit cleanly in that case.
package splice.app

import kotlinx.coroutines.cancel
import splice.app.provider.HeadBuildInputs
import splice.app.provider.ProviderAssembly
import splice.control.ControlServer
import splice.control.DashboardPage
import splice.control.FailedHeads
import splice.control.LaunchService
import splice.control.ManagedHead
import splice.control.ShutdownDaemon
import splice.control.TurnPathStalled
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.launch.ClaudeConfigMaterializer
import splice.core.util.LogSink
import splice.spi.LifecycleScope
import splice.spi.ProcessDispatchers
import java.nio.file.Path

internal class ControlPlane(
    private val statePaths: StatePaths,
    private val config: ConfigService,
    private val mgmtKey: MgmtKey,
    private val dashboardHtml: DashboardPage,
    private val log: LogSink,
    private val shutdownDaemon: ShutdownDaemon,
    // JW-04: the booted config identity (sha-256 of the parsed bytes + the resolved path).
    private val topologyDigest: String = "",
    private val topologyPath: Path? = null,
    refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh()::refresh),
) {
    private val boundary = DaemonBoundary()

    // Held here so Daemon can drop the app.provider + spi imports (concentration, 2026-08-19).
    // probeScope is the daemon's OWN scope — ProviderAssembly must receive the SAME instance
    // stop() cancels; constructing a second one would leak prefetch coroutines.
    internal val signInPlanner = SignInPlanner()
    internal val buildInputs = HeadBuildInputs(config, signInPlanner)
    internal val probeScope = LifecycleScope(ProcessDispatchers().background())
    internal val providerAssembly = ProviderAssembly(statePaths, probeScope, log, refreshCall)

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
        val srv = ControlServer(
            controlPort,
            heads,
            config,
            mgmtKey,
            dashboardHtml,
            log,
            LaunchService(ClaudeConfigMaterializer(statePaths.rootDir.parent ?: statePaths.rootDir)),
            shutdownDaemon,
            failedHeads,
            headCount,
            topologyDigest = topologyDigest,
            configPath = topologyPath?.toString().orEmpty(),
            topologyStale = TopologyLoader.staleProbe(topologyPath, topologyDigest),
            turnPathStalled = turnPathStalled,
        )
        val controlBound = boundary.runCatchingDaemonBoundary { srv.start() }
            .onFailure {
                // SAFE-RENDER-EXEMPT[2026-08-31]: srv.start() bind failure — a SocketException names a port and an address, never file bytes
                log("[daemon] control plane could not bind :$controlPort (${it.message}); another owns it, exiting\n")
                shutdownDaemon()
            }
            .isSuccess
        return if (controlBound) srv else null
    }
}
