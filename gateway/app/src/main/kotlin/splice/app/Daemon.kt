// NEW: the daemon assembly (P4-SUP) — one JVM hosting the control plane + every enabled head.
// Builds each head from topology (provider wired to its dialect + auth + stores), starts control
// :3096 and each head port. suspend all the way (the runBlocking bridge lives in Main); version
// handshake = /health version string equality (a daemon bump restarts all heads together — the
// documented change).
//
// SHAPE (Kotlin style law, 2026-08-15, decomposed further 2026-08-17): what used to be file-level
// helpers, then same-file collaborators, are now named collaborators in two owned sub-packages —
// splice.app.head (boot, probes, shutdown, per-head assembly) and splice.app.provider (the whole
// (dialect, auth.kind) dispatch) — plus three sibling root files (DaemonBoundary, DashboardHtml,
// ControlPlane). Daemon itself keeps only its constructor/fields and start()/stop(), and delegates
// everything else to the collaborators it wires together.
package splice.app

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.app.head.HEAD_STOP_BUDGET_MS
import splice.app.head.HeadBoot
import splice.app.head.HeadProbes
import splice.app.head.HeadServerFactory
import splice.app.head.HeadShutdown
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.control.ControlServer
import splice.control.DashboardPage
import splice.control.ManagedHead
import splice.control.ShutdownDaemon
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.LogSink
import java.nio.file.Path

public class Daemon(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val dashboardHtml: DashboardPage,
    private val log: LogSink = LogSink { System.err.print(it) },
    private val shutdownDaemon: ShutdownDaemon = ShutdownDaemon {},
    private val refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh()::refresh),
    // JW-04: the booted config identity (sha-256 of the parsed bytes + the resolved path).
    // Defaults keep every existing test constructor compiling; Main always passes both.
    private val topologyDigest: String = "",
    private val topologyPath: Path? = null,
) {
    // Topology TOML ([daemon] + [defaults]) feeds the headOverrides layer so reasoning
    // display is operator-editable without recompiling. Env and runtime PATCH still win.
    // [heads.<key>.overrides] rides the per-head layer: heads share ONE ConfigService (one JVM,
    // unlike the Node lineage's process-per-head), so without this a knob tuned for one upstream
    // hit all of them — e.g. kimi's 40-min upstreamTimeoutMs also gave codex a 40-min ceiling.
    private val config = ConfigService(
        statePaths,
        headOverrides = TopologyKnobLayer(topology).configOverrides(),
        perHeadOverrides = topology.heads.mapValues { (_, head) -> head.overrides },
    )
    private val mgmtKey = MgmtKey(statePaths)
    private val controlPlane = ControlPlane(
        statePaths, config, mgmtKey, dashboardHtml, log, shutdownDaemon,
        topologyDigest, topologyPath, refreshCall,
    )

    // The collaborators the file-level/same-file helpers became (Kotlin style law, 2026-08-15;
    // diffused into splice.app.head / splice.app.provider, 2026-08-17). All are stateless or hold
    // only their own probe bookkeeping; one instance each keeps the wiring readable.
    private val headBoot = HeadBoot()
    private val headProbes = HeadProbes()
    private val headShutdown = HeadShutdown()

    // internal, not private: DaemonPerHeadConfigTest calls buildInputs.providerContext(...)
    // directly to pin that each head resolves against getConfig(key) — see HeadBuildInputs' KDoc.
    // Inferred so this file does not name HeadBuildInputs (concentration, 2026-08-19).
    internal val buildInputs get() = controlPlane.buildInputs
    private val headServerFactory = HeadServerFactory(config, mgmtKey, log)
    private val launchSpecFactory = LaunchSpecFactory(topology, controlPlane.signInPlanner, mgmtKey, controlPlane.buildInputs)
    private val managedHeadFactory = ManagedHeadFactory(statePaths, controlPlane.providerAssembly, headServerFactory, launchSpecFactory)

    // set once in start(); the daemon is not usable before it
    private var control: ControlServer? = null
    private val heads = LinkedHashMap<String, ManagedHead>()
    private val stopLock = Mutex()
    private var stopped = false

    public suspend fun start() {
        val cfg = config.getConfig()
        // TOML feeds ConfigService's topology layer; state/env/runtime override it consistently.
        // Resolved before the head loop so every launch recipe points at the actual listener.
        val controlPort = cfg.controlPort
        // PER-HEAD BOOT ISOLATION (audit 2026-07-18): one head that fails to assemble (a valid
        // TOML the builder can't wire, e.g. a not-yet-supported dialect) must NOT abort the whole
        // daemon with a stack trace to /dev/null. Log the degraded head and serve the rest.
        val failed = headBoot.assembleDaemonHeads(topology, statePaths, heads, log) { key, head, providerCfg ->
            managedHeadFactory.assembleHead(buildInputs.providerContext(key, head, providerCfg), controlPort)
        }
        // Start heads BEFORE opening the control plane so a launch-shim that sees /health and
        // immediately POSTs /launch/<head> does not race a still-binding head (503 head is not
        // running) — headProbes.startDaemonHeads binds every head's port; controlPlane.start below
        // binds the control port, so it must run after.
        headProbes.startDaemonHeads(heads, failed, controlPlane.probeScope, log)
        val srv = controlPlane.start(
            controlPort = controlPort,
            heads = heads,
            failedHeads = { failed.size },
            headCount = topology.heads.size,
            turnPathStalled = { headProbes.stalledKeys() },
        ) ?: return
        control = srv
        val degraded = if (failed.isEmpty()) "" else " DEGRADED=${failed.keys}"
        log("[daemon] up: control :$controlPort, heads ${heads.keys}$degraded\n")
    }

    public suspend fun stop(): Unit = stopLock.withLock {
        if (!stopped) {
            stopped = true
            headProbes.stop()
            controlPlane.cancelProbes()

            // Heads stop in PARALLEL under a phase DEADLINE, then control stops — see
            // [HeadShutdown.stopHeads]. The supervisor scope + stopFailureHandler live there so an
            // exception escaping one head's stop (a type outside runCatchingDaemonBoundary's list)
            // can't cancel the siblings' drains/flushes nor skip control.stop — it surfaces on
            // stderr/daemon.log instead of the JVM default, a black hole once production redirects
            // stderr to /dev/null.
            headShutdown.stopHeads(heads.values.map { it.head }, HEAD_STOP_BUDGET_MS, log) { control?.stop() }
        }
    }
}
