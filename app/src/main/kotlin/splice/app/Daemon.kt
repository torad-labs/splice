// NEW: the daemon assembly (P4-SUP) — one JVM hosting the control plane + every enabled head.
// Builds each head from topology (provider wired to its dialect + auth + stores), starts control
// :3096 and each head port. suspend all the way (the runBlocking bridge lives in Main); version
// handshake = /health version string equality (a daemon bump restarts all heads together — the
// documented change).
//
// SHAPE (Kotlin style law, 2026-08-15, decomposed further 2026-08-17): what used to be file-level
// helpers, then same-file collaborators, are now named collaborators in two owned sub-packages —
// splice.app.head (boot, probes, shutdown, per-head assembly) and splice.app.provider (the
// compatibility-checked auth-kind/dialect dispatch and provider-specific data selection) — plus
// two sibling root files (DaemonBoundary, ControlPlane). Daemon keeps only its
// constructor, fields, start(), and stop(); everything else delegates to those collaborators.
package splice.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splice.app.control.FailedHeads
import splice.app.control.HandOver
import splice.app.control.ManagedHead
import splice.app.daemon.BootedTopology
import splice.app.daemon.HeadCatalogs
import splice.app.daemon.TopologyWindows
import splice.app.desktop.DaemonDesktop
import splice.app.desktop.setup.DesktopAppLibrary
import splice.app.desktop.setup.DesktopPolicyOverrides
import splice.app.head.HEAD_STOP_BUDGET_MS
import splice.app.head.HeadAgentTools
import splice.app.head.HeadBoot
import splice.app.head.HeadCapabilities
import splice.app.head.HeadKeptFiles
import splice.app.head.HeadMcpServers
import splice.app.head.HeadModeHooks
import splice.app.head.HeadOthers
import splice.app.head.HeadProbes
import splice.app.head.HeadPromptInputs
import splice.app.head.HeadServerFactory
import splice.app.head.HeadServing
import splice.app.head.HeadShutdown
import splice.app.head.LaunchSpecFactory
import splice.app.head.ManagedHeadFactory
import splice.app.head.QuotaPollSeams
import splice.app.head.ServedProviderAssembly
import splice.app.head.books.BooksKeys
import splice.app.modes.DaemonModes
import splice.app.modes.ModeBoot
import splice.app.modes.ModeHooks
import splice.app.modes.ModeServerRoutes
import splice.app.modes.ModeTools
import splice.app.probe.LocalWindowRefresh
import splice.app.provider.AntigravityJoin
import splice.app.server.DaemonServer
import splice.app.share.DaemonShare
import splice.client.desktop.DesktopAppConfig
import splice.client.mcp.McpShareChoice
import splice.client.resume.originals.TranscriptOriginals
import splice.codemode.WorkerArtifacts
import splice.core.compaction.CompactionInstructions
import splice.core.compaction.SessionProject
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.retired.RetiredHeads
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig
import splice.core.topology.ProviderFamilyRule
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import splice.core.util.LogSink
import splice.core.version.ClientVersionTracker
import splice.head.compaction.CompactionTail
import splice.lifecycle.restart.ShutdownDaemon
import splice.models.roster.DeclaredHead
import splice.models.roster.DeclaredHeads
import splice.modes.api.ModeKind
import splice.oauth.codex.CodexRefresh
import splice.share.RemoteTrees
import splice.topology.TopologyLoader
import splice.usage.quota.ClientUserAgent
import java.nio.file.Path
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.TimeUnit

public class Daemon(
    private val topology: Topology,
    private val statePaths: StatePaths,
    private val log: LogSink = LogSink { System.err.print(it) },
    private val shutdownDaemon: ShutdownDaemon = ShutdownDaemon {},
    private val refreshCall: TokenUrlRefreshCall = TokenUrlRefreshCall(CodexRefresh(log)::refresh),
    // JW-04: the booted config identity (sha-256 of the parsed bytes + the resolved path).
    // Defaults keep every existing test constructor compiling; Main always passes both.
    private val topologyDigest: String = "",
    private val topologyPath: Path? = null,
) {
    init {
        WorkerArtifacts.pinAtBoot()
    }

    /** The sockets a manager handed this process, assigned by Main before [start] (see [AdoptedServing]). Assigned
     *  rather than a constructor parameter: this constructor is at its width ceiling, and nothing reads the sockets
     *  until [start] opens the listeners. The default binds every port itself, which is every start without one. */
    internal var adopted: AdoptedServing = AdoptedServing()

    /** #523: how the control plane asks this process to hand over to the next daemon (POST /api/daemon/retire) and
     *  asks the launcher for one, assigned by Main before [start]. Null, every daemon a test builds, answers that
     *  route with a named 503. */
    internal var handOver: HandOver? = null

    /** The modes this jar carries, as boot read them (modes/README.md), assigned by Main before [start]: the daemon
     *  then holds them with splice.toml's switches, for the control plane's routes and every head's door. */
    internal var modeBoot: ModeBoot? = null
        set(boot) {
            field = boot
            controlPlane.modes = boot?.let {
                DaemonModes(it.catalog, topology.modes, topology.heads.keys.toList(), statePaths.stateDir)
            }
        }

    /** The modes as this daemon holds them; null until Main assigns [modeBoot], and in a daemon a test builds. */
    internal val modes: DaemonModes? get() = controlPlane.modes

    // Topology TOML ([daemon] + [defaults]) feeds the headOverrides layer so reasoning
    // display is operator-editable without recompiling. Env and runtime PATCH still win.
    // [heads.<key>.overrides] rides the per-head layer: heads share ONE ConfigService (one JVM,
    // unlike the Node lineage's process-per-head), so without this a knob tuned for one upstream
    // hit all of them — e.g. kimi's 40-min upstreamTimeoutMs also gave codex a 40-min ceiling.
    private val overrideLayers = TopologyKnobLayer(topology).layers()
    private val config = ConfigService(
        statePaths,
        headOverrides = TopologyKnobLayer(topology).configOverrides(),
        perHeadOverrides = overrideLayers.own,
        inheritedOverrides = overrideLayers.inherited,
    ).also { it.observeSnapshots(splice.upstream.memory.JvmHeap.budget) }
    private val mgmtKey = MgmtKey(statePaths)
    private val clientVersions = ClientVersionTracker()

    // Nov 12, 2026: the price table follows the project's main branch between releases (PriceRefresh). The table the
    // last refresh kept is taken here, before any catalog below takes a price, when it is not older than this build's.
    private val prices = LifecycleWiring.priceRefresh(statePaths, topologyPath, log).also { it.seed() }

    /** The topology's directory: a relative `system_prompt_file` or `[compaction] file =` resolves
     *  against it. Hoisted above [controlPlane] (V4-136) because the shared compaction resolver
     *  below needs it, and that resolver is handed to the control plane. */
    private val topologyDir = topologyPath?.parent ?: TopologyLoader.configPath().parent

    /** V4-136: ONE compaction resolver, SHARED. The console route reports the resolver the daemon
     *  actually compacts with — including its live file cache — rather than a rebuilt copy that
     *  would agree with this one only by luck. Constructed once and handed to both [compactionTail]
     *  and [controlPlane], so there is no second instance to drift. */
    private val compactionInstructions =
        CompactionInstructions(topology.compaction, topologyDir, log = log)

    /** V4-162: the context windows splice.toml declares, re-read while the daemon runs, and the
     *  version the control plane publishes as running. Hoisted above [controlPlane], which reports
     *  it; its catalogs resolve through [buildInputs] at re-read time, after start() built the heads. */
    private val topologyWindows: TopologyWindows = TopologyWindows(
        topologyPath,
        topology,
        topologyDigest,
        HeadCatalogs { key, head, provider, legacy -> buildInputs.catalogFor(key, head, provider, legacy) },
        log,
    ).also { it.compaction = compactionInstructions }

    private val controlPlane = ControlPlane(
        DaemonEnvironment(statePaths, config, mgmtKey, log),
        shutdownDaemon,
        // The booted config's identity and what it declared, as one value — three parameters until
        // the width ratchet caught this constructor at 13. declaredHeads is still built HERE and not
        // in ControlPlane, because this is the only place that holds the Topology: ControlPlane
        // carries the digest and the path, never the object, and a second read of the file the heads
        // were built from can diverge from it.
        BootedTopology(
            digest = topologyDigest,
            path = topologyPath,
            declaredHeads = DeclaredHeads {
                topology.heads.mapValues { (_, head) ->
                    val family = topology.providers[head.provider]?.let { ProviderFamilyRule().of(head.provider, it) }
                    // One row per declared tier: a model behind two tiers is two declared slots.
                    val tiers = head.tierModels()
                    val declared = head.models?.flatMap { model ->
                        tiers.filterValues { it == model.id }.keys.map { model.copy(slot = it) }
                            .ifEmpty { listOf(model.copy(slot = null)) }
                    } ?: tiers.map { (slot, id) -> HeadModel(id, slot) }
                    DeclaredHead(head.provider, declared, family)
                }
            },
            running = topologyWindows,
            alertCommand = topology.daemon.alertCommand.orEmpty(),
            reservedPeerNames = topology.daemon.reservedPeerNames.orEmpty().toSet(),
            remote = topology.remote,
        ),
        refreshCall,
        mcpHosting = McpHostingSettings(share = McpShareChoice { topologyWindows.mcpShare }).with(topology.daemon),
        clientVersions = clientVersions,
        compactionInstructions = compactionInstructions,
    ).also {
        // `[server.probe]`: how every pool's servers are checked, set before any head (and so any pool) is built.
        // Assigned rather than passed: every constructor between here and the pools is at its width ceiling.
        it.providerAssembly.upstreamPools.configure(topology.server.probe, topologyDir)
    }

    // The collaborators the file-level/same-file helpers became (Kotlin style law, 2026-08-15;
    // diffused into splice.app.head / splice.app.provider, 2026-08-17). All are stateless or hold
    // only their own probe bookkeeping; one instance each keeps the wiring readable.
    // #525: each head is started as soon as it is assembled (HeadBoot).
    private val headBoot = HeadBoot(
        HeadsStart { built, failed -> headProbes.startDaemonHeads(built, failed, controlPlane.probeScope, log) },
    )
    private val performance = splice.app.observability.PerfTelemetry(
        config,
        topology.heads.keys,
        controlPlane.perfRows,
        controlPlane.alerts,
        log,
    ).also { controlPlane.performance = it }
    private val headProbes = HeadProbes()
    private val headShutdown = HeadShutdown()

    // internal, not private: DaemonPerHeadConfigTest calls buildInputs.providerContext(...)
    // directly to pin that each head resolves against getConfig(key) — see HeadBuildInputs' KDoc.
    // Inferred so this file does not name HeadBuildInputs (concentration, 2026-08-19).
    internal val buildInputs get() = controlPlane.buildInputs

    // The directory a relative `file =` / `system_prompt_file =` resolves against: the topology's
    // own directory, so a config kept beside its text files moves as one unit.
    private val launchSpecFactory = LaunchSpecFactory(
        topology,
        controlPlane.signInPlanner,
        mgmtKey,
        controlPlane.buildInputs,
    )

    // V4-130: ONE session-to-cwd resolver over every head's projects tree (then the vanilla one), for
    // both the compaction tail and the heads' prompt layers. The vanilla-only default missed every
    // headless session of a head that keeps its own tree.
    private val sessionProject = SessionProject(
        headProjectsDirs = launchSpecFactory.headProjectsTrees(),
        heap = splice.upstream.memory.JvmHeap.budget,
    )
    private val compactionTail = CompactionTail(compactionInstructions, sessionProject)
    private val headServerFactory =
        HeadServerFactory(
            config,
            mgmtKey,
            log,
            compactionTail,
            clientVersions,
            HeadPromptInputs(topologyDir, topology.projects, sessionProject),
            // V4-134: the control plane's publisher, so every head reports to the stores the console reads.
            console = controlPlane.console,
        ).also {
            it.credentialAccountNames = controlPlane.credentialAccountNames
            it.sentCredentials = controlPlane.sentCredentials
            it.performance = performance
            it.capabilities = HeadCapabilities(
                topology.capabilities,
                statePaths.headsDir,
                controlPlane.gatherers,
                HeadMcpServers(launchSpecFactory.headConfigDirs(), statePaths),
                HeadAgentTools { key ->
                    controlPlane.modes?.takeIf { m -> m.catalog.modes.any { it.manifest.kind == ModeKind.DOOR } }
                        ?.let { ModeTools(it, key) }
                },
                hooks = HeadModeHooks { key ->
                    controlPlane.modes?.takeIf { m -> m.catalog.modes.any { it.hooks.isNotEmpty() } }
                        ?.let { ModeHooks(it, key) }
                },
            )
        }
    private val managedHeadFactory = ManagedHeadFactory(
        statePaths = statePaths,
        providerAssembly = controlPlane.providerAssembly,
        serving = HeadServing(headServerFactory, controlPlane.playgroundProviders),
        launchSpecFactory = launchSpecFactory,
        log = log,
        quotaSeams = QuotaPollSeams(
            controlPlane.probeScope,
            log,
            // The probes present the Claude Code this daemon has actually seen, which is the identity Anthropic's
            // usage endpoint buckets by (ClaudeUsageProbe). One tracker, the same one every head observes into.
            clientUserAgent = ClientUserAgent(clientVersions::newestClaudeCodeUserAgent),
            readFailures = controlPlane.quotaReadFailures,
        ),
        perfSources = controlPlane.perfRows,
    ).also { it.sessionFallbacks = controlPlane.sessionFallbacks }

    // #525 (Nov 13, 2026): the heads in splice.toml's order whatever order they were assembled in, and safe to read
    // while a later head is still being added, since a head now serves before the last one is assembled.
    private val headOrder = topology.heads.keys.withIndex().associate { (index, key) -> key to index }
    private val heads = ConcurrentSkipListMap<String, ManagedHead>(
        compareBy<String> { headOrder[it] ?: Int.MAX_VALUE }.thenBy { it },
    )

    /** Every provider in splice.toml, served by the gateway on its own, with no head (gateway-no-heads). */
    private val providers = ServedProviderAssembly(topology, buildInputs, managedHeadFactory, log)

    /** LAN share: the commands this machine runs on another's heads, and the listener that offers its own. */
    private val share = DaemonShare(topology, log, statePaths.stateDir)

    /** Server mode: a team's keys and the listener that answers them, when splice.toml has a `[server]`. */
    private val server = DaemonServer(
        topology,
        config,
        TurnKey(mgmtKey),
        log,
        statePaths.stateDir,
        topologyDir,
        controlPlane.providerAssembly.upstreamPools,
    )

    /** Claude Desktop's Code tab (docs/specs/desktop-door.md): its door on the control port, and `splice desktop`. */
    private val desktop = DaemonDesktop(
        topology,
        statePaths,
        log,
        DesktopAppLibrary(
            DesktopAppConfig(statePaths.envReader),
            overrides = DesktopPolicyOverrides(statePaths.desktopPolicyOverridesFile),
            log = log,
        ),
        turnKey = TurnKey(mgmtKey),
        sessionSpeeds = controlPlane.providerAssembly.sessionSpeeds,
    )

    /** desktop-antigravity: Antigravity, a provider only Claude Desktop runs, once agy says what it offers; never asked
     *  while Antigravity is off: by the console's Desktop harnesses, else splice.toml's `[daemon] antigravity = false`
     *  (desktop-harnesses), read as the daemon starts. */
    private val antigravity = AntigravityJoin(config, log, enabled = desktop.harnesses.boot())
    private val stopLock = Mutex()
    private var stopped = false

    // #523: set once this daemon has handed over to the next one; its stop then closes only its heads.
    private var retired = false

    public suspend fun start() {
        performance.start()
        // Before anything binds: the control plane and every head serve on a handed-over socket where there is one.
        controlPlane.adopted = adopted
        controlPlane.handOver = handOver
        headServerFactory.adopted = adopted
        // door-default, decision 9: the head keyed `claude` hands the door's models to their heads once the door opens.
        headServerFactory.others = HeadOthers(desktop.headLane::forHead)
        val cfg = config.getConfig()
        // TOML feeds ConfigService's topology layer; state/env/runtime override it consistently.
        // Resolved before the head loop so every launch recipe points at the actual listener.
        val controlPort = cfg.controlPort
        // PER-HEAD BOOT ISOLATION (audit 2026-07-18): one head that fails to assemble (a valid
        // TOML the builder can't wire, e.g. a registered auth kind on an incompatible dialect) must
        // NOT abort the whole daemon with a stack trace to /dev/null. Log the degraded head and
        // serve the rest.
        // DR-80: the legacy single-head knobs may govern only the sole head of their kind — for
        // two-plus same-kind heads nothing was seeded, and the unconditional overwrite handed
        // every sibling the first head's (or the default) port/model/base.
        val legacySolo = TopologyKnobLayer(topology).soleLegacyHeadKeys()
        // V4-260: a head removed from splice.toml leaves no compaction answers, code-mode state or
        // trace days behind; this start is the first moment it is known to be gone.
        // A command removed from Models is recorded as retired and keeps everything it wrote.
        val retiredHeads = RetiredHeads(statePaths.stateDir)
        // A command added back is no longer retired: its history joins it again, once.
        retiredHeads.all().filter { it.key in topology.heads }.forEach { retiredHeads.rejoin(it.key) }
        val retired = retiredHeads.all().map { it.key }
        // The providers a gateway turn runs on keep their state under their own keys, which no head of that name held.
        HeadKeptFiles(statePaths, log).ofRemovedHeads(topology.heads.keys + retired + BooksKeys(topology).all())
        TranscriptOriginals(statePaths).sweep(log)
        // 2026-09-22: every head's endpoint is asked what it serves — all at once, bounded, before any
        // catalog exists — so each picker is its declared rows plus what its provider lists.
        val rosterProviders = rosterProviders(legacySolo)
        controlPlane.modelRosters.resolve(rosterProviders)
        controlPlane.modelRosters.start(controlPlane.probeScope, rosterProviders)
        // Start heads BEFORE opening the control plane so a launch-shim that sees /health and
        // immediately POSTs /launch/<head> does not race a still-binding head (503 head is not
        // running) — the assembly starts each head through headProbes.startDaemonHeads, which binds
        // that head's port; controlPlane.start below binds the control port, so it must run after.
        val failed = assembleHeads(controlPort, legacySolo)
        // After the heads, which build the shared books first: one runtime per provider, serving the gateway.
        providers.open()
        headProbes.startRuntimeWatch(
            topology,
            controlPlane.probeScope,
            log,
            LocalWindowRefresh(controlPlane.buildInputs::refreshLocalModels),
        )
        controlPlane.remoteCommands = share.remotes(
            RemoteTrees(launchSpecFactory::remoteTrees),
            launchSpecFactory.remotePolicy(),
        )
        // The share listener opens before the control plane too: `splice share on` restarts, waits for /health, and
        // reads the share port at once.
        // door-network: it answers the door too, which opens below, so a turn that comes first is told to wait.
        share.open(desktop.network)
        // And the server listener, with its keys: `splice server key create` asks the control plane for them.
        server.open(heads, headServerFactory.served, desktop.team, modes?.let(::ModeServerRoutes))
        controlPlane.serverMode = server
        // The desktop door, on the control port, choosing among these heads.
        desktop.open(providers, antigravity, controlPort, controlPlane.modelRosters)
        controlPlane.desktop = desktop
        joinAntigravity()
        controlPlane.start(
            controlPort = controlPort,
            heads = heads,
            failedHeads = object : FailedHeads {
                override fun invoke(): Int = failed.size

                override fun reasons(): Map<String, String> = failed.toMap()
            },
            headCount = topology.heads.size,
            probes = headProbes,
        ) ?: return closeListeners()
        // MCP hub step 6: each installed Claude Code version's --plugin-dir-no-mcp verdict, checked in the background.
        controlPlane.pluginFlagChecks.request()
        prices.start()
        val degraded = if (failed.isEmpty()) "" else " DEGRADED=${failed.keys}"
        log("[daemon] up: control :$controlPort, heads ${heads.keys}$degraded\n")
    }

    /** desktop-antigravity: Antigravity's models are asked of agy in the background, since agy asks Google for them;
     *  once they arrive Desktop's list is published again with them. */
    private fun joinAntigravity() {
        controlPlane.probeScope.launch {
            if (antigravity.join(controlPlane.probeScope, providers)) desktop.republish()
        }
    }

    /** Assembles every head and starts each one as it is built (HeadBoot). #525: the first heads serve before the
     *  last is assembled, and no re-read of splice.toml runs until every head has attached to it. */

    /** Every endpoint asked what it serves: each head's provider by head key, and each provider the gateway serves with
     *  no head by the key its books live under. */
    private fun rosterProviders(legacySolo: Set<String>): Map<String, ProviderConfig> =
        topology.heads.mapNotNull { (key, head) ->
            val declared = topology.providers[head.provider] ?: return@mapNotNull null
            key to buildInputs.effectiveProvider(key, declared, legacyKnobsGovern = key in legacySolo)
        }.toMap() + providers.rosterProviders()

    private suspend fun assembleHeads(controlPort: Int, legacySolo: Set<String>): Map<String, String> {
        topologyWindows.assembling = true
        try {
            return headBoot.assembleDaemonHeads(topology, statePaths, heads, log) { key, head, providerCfg ->
                val legacy = key in legacySolo
                val ctx = buildInputs.providerContext(key, head, providerCfg, legacyKnobsGovern = legacy)
                // V4-162: attached before assembly, so every holder of the catalog (provider, launch spec,
                // statusline) reads the windows splice.toml declares NOW.
                managedHeadFactory.assembleHead(topologyWindows.attach(ctx, legacy), controlPort)
            }
        } finally {
            topologyWindows.assembling = false
        }
    }

    /** #523: the hand-over to the next daemon, which is the stop below with one difference: a head whose provider
     *  can finish an answer beside another daemon keeps the turns already answering. Everything else this process
     *  holds that the next daemon needs is closed here, in the stop's own order (the share listener, the control
     *  server, the probes and sign-ins, the performance recorder), but for what rides on the control server, which
     *  stops first, and the control server's engine: here it stops taking connections, and it stops in [finish]. When
     *  this returns the caller releases the daemon lock and the next daemon boots; [finish] then waits for the kept
     *  turns. Returns how many were kept.
     *
     *  The probe scope ends here although kept turns are still streaming, which the stop never allows. It has to: a
     *  login's renewal and an open sign-in are the next daemon's from the hand-over on, and two daemons renewing one
     *  login would spend its refresh token twice. So a kept turn cannot renew its login in this process. It needs
     *  to only for a second upstream attempt after its token ran out, and that attempt fails here. */
    public suspend fun retire(): Int = stopLock.withLock {
        if (stopped || retired) return@withLock 0
        // A hosted MCP child takes up to two seconds to die. It dies first, while the heads still take turns: after
        // them, that wait was part of the stretch in which no daemon serves (2.4 to 2.5 s of it in eight of the
        // thirteen hand-overs of Nov 12, 2026).
        controlPlane.ownership.quiet()
        headProbes.fenceStarts()
        controlPlane.ownership.fence()
        closeListeners()
        headProbes.closeStarts()
        val routes = heads.values.map { it.head } + providers.running()
        val kept = headShutdown.retireHeads(routes, HEAD_STOP_BUDGET_MS, log) {
            controlPlane.ownership.leave()
        }
        val handedOver = System.nanoTime()
        closeBackground()
        // #525 (2026-11-13): the lock is freed when this returns, and the next daemon boots from there. No line said
        // how long this daemon still held it: the stretch from the heads' hand-over to the next daemon's first line
        // read 0.22 s at one restart of that day and 0.44 s at the next two, and nothing told this daemon's part of
        // it from the next one's.
        val closedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - handedOver)
        log("[daemon] retire: background work closed ${closedMs}ms after the heads; the lock is freed next\n")
        retired = true
        kept
    }

    /** Finish retained replies before closing the old control engine. No daemon lock is held while they end. */
    public suspend fun finish(withinMs: Long) {
        val routes = heads.values.map { it.head } + providers.running()
        headShutdown.finishHeads(routes, withinMs, log) { controlPlane.ownership.close() }
    }

    public suspend fun stop(): Unit = stopLock.withLock {
        if (!stopped) {
            // Both fences first and without waiting: a stop cut short by its deadline while a head start holds the
            // gate must still keep startup from starting a head or binding the control port after it. [stopped] is set
            // at the END, so a pass that was cut short is finished by the next call, never taken for done.
            headProbes.fenceStarts()
            controlPlane.ownership.fence()
            // No other machine's turn starts on a head that is about to drain. Server mode's drain (#791,
            // `[server] drain_seconds`) runs here, while the heads still serve the turns it waits for, and what it
            // spent comes out of the heads' budget, so the whole stop still ends inside Main's cooperative cap.
            val began = System.nanoTime()
            if (!retired) closeListeners()
            val listenersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)

            // Heads stop in PARALLEL under a phase DEADLINE, then control stops — see
            // [HeadShutdown.stopHeads]. The supervisor scope + stopFailureHandler live there so an
            // exception escaping one head's stop (a type outside runCatchingDaemonBoundary's list)
            // can't cancel the siblings' drains/flushes nor skip control.stop — it surfaces on
            // stderr/daemon.log instead of the JVM default, a black hole once production redirects
            // stderr to /dev/null.
            headProbes.closeStarts()
            val routes = heads.values.map { it.head } + providers.running()
            val headBudgetMs = (HEAD_STOP_BUDGET_MS - listenersMs).coerceAtLeast(0L)
            headShutdown.stopHeads(routes, headBudgetMs, log) { controlPlane.ownership.close() }

            // A daemon that handed over closed these then, before the next daemon opened its own.
            if (!retired) closeBackground()
            stopped = true
        }
    }

    /** The two listeners that bring other machines' turns to the heads here: LAN share's and server mode's. */
    private suspend fun closeListeners() {
        share.close()
        server.close()
    }

    /** What runs beside the heads, closed once the heads no longer take turns. Probe cancellation runs AFTER the
     *  heads have drained: the probe scope is the scope ProviderAssembly hands every provider, so cancelling it first
     *  means a SingleFlight token refresh raised by a turn still streaming inside the drain is cancelled by a job
     *  that turn does not own — a foreign CancellationException in a live turn. */
    private suspend fun closeBackground() {
        antigravity.close()
        prices.close()
        headProbes.stop()
        managedHeadFactory.closeEvidenceLoads()
        controlPlane.cancelProbes()
        performance.close()
        topologyWindows.close()
    }
}
