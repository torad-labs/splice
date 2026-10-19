// Console route adapters and stores, assembled after the control server is constructed.
package splice.app

import kotlinx.coroutines.CoroutineScope
import splice.app.auth.ConsoleAccountsImpl
import splice.app.console.DrainingRestartAdapter
import splice.app.control.ControlServer
import splice.app.daemon.BootedTopology
import splice.app.sources.mcp.McpSessionsByPid
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.model.HeadDiscoveredModels
import splice.core.storage.ACTIVITY_DIRECTORY
import splice.core.storage.RetentionDays
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.diagnostics.doctor.DoctorReport
import splice.diagnostics.playground.PlaygroundProbe
import splice.lifecycle.upgrade.AutoUpdateSettings
import splice.lifecycle.upgrade.ConsoleUpgradeStatus
import splice.lifecycle.upgrade.UpgradeChecks
import splice.lifecycle.upgrade.UpgradeStatus
import splice.sessions.activity.ALL_HEADS
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.EdgesStored
import splice.sessions.activity.LiveEdges
import splice.sessions.activity.NameHolders
import splice.sessions.prompt.SessionAddress
import splice.sessions.teams.TEAMS_FILE
import splice.sessions.teams.TeamStore
import splice.topology.TopologyLoader
import splice.upstream.memory.JvmHeap
import splice.usage.alerts.ALERTS_FILE
import splice.usage.alerts.AlertDelivery
import splice.usage.alerts.AlertStore
import splice.usage.budgets.BUDGETS_FILE
import splice.usage.budgets.BudgetEnforcement
import splice.usage.budgets.BudgetStore
import splice.usage.budgets.HeadPerfHistory
import java.nio.file.Path

internal object ConsoleWiring {
    internal fun wire(
        srv: ControlServer,
        topology: BootedTopology,
        discovered: HeadDiscoveredModels,
        configBackups: Path,
        log: LogSink,
    ) {
        // V4-127: the console's three read ports, assigned after construction because a constructor
        // parameter would widen ControlServer past the width ratchet — so the compiler cannot check
        // any of these lines, and deleting one does not break the build. Each route then answers its
        // NAMED 5xx, and the upgrade one is the worst of them: an unwired port there reads as a
        // measured payload saying nothing is newer, which tells an operator they are up to date when
        // nobody has ever looked. The deletion pins in ConsoleWiringPinTest are what make that a red
        // instead of a quiet lie.
        srv.ports.declaredHeads = topology.declaredHeads
        srv.ports.doctor = DoctorReport(DoctorWiring::consoleJson)
        srv.ports.doctorFixes = DoctorWiring.fixes()
        val upgradeChecks = UpgradeChecks(
            EnvReader(System::getenv),
            settings = topology.path?.let { LifecycleWiring.updateSettings(it, log) } ?: AutoUpdateSettings { null },
        )
        srv.ports.lifecycle.upgradeChecks = upgradeChecks
        val upgradeStatus = ConsoleUpgradeStatus(EnvReader(System::getenv), upgradeChecks)
        srv.ports.lifecycle.upgrade = UpgradeStatus(upgradeStatus::json)
        // V4-137: the draining restart's supervision probe. Unlike the three above, leaving this one
        // unassigned is SAFE BY CONSTRUCTION — ConsolePorts.supervised is null until set and the
        // route refuses on null, so an unwired port declines to drain rather than draining a daemon
        // nothing would restart. It is assigned here anyway because the refusal is not the answer we
        // want on a host where systemd does run the daemon, and pinned for the same reason the others
        // are: the compiler cannot see this line either.
        srv.ports.lifecycle.supervised = DrainingRestartAdapter()

        // V4-128: the writer is the ONLY seam that edits splice.toml, and it is built here because
        // this is the one place that knows the booted file's path. A null path is a daemon booted
        // without a config file: the route declines rather than writing a file nobody asked for.
        // V4-284: its backups go to splice's own [configBackups], never beside the file.
        srv.ports.topology = topology.path?.let {
            TopologyWriter(it, configBackups, TopologyParse(TopologyLoader::parse), discovered = discovered)
        }

        // V4-132: the login/remove/relabel machinery — :daemon-control depends on :core only, so this is
        // the port's :app-side implementation, assigned here like every port above it. Unassigned,
        // the four routes behind it answer a named 503 rather than a payload reading as "no
        // accounts".
        srv.ports.accounts = ConsoleAccountsImpl()

        // V4-220 item 3: the SAME keys.toml every api-key head reads (ApiKeyAuthProvider's default is
        // KeyStorePath.defaultPath() over this process's environment), so a console PUT lands in the
        // file those heads read on their next request. Its unreadable-store line goes to the daemon log.
        srv.ports.keys = KeyStore(KeyStorePath.defaultPath())

        // V4-220 item 3: `splice add` over /api/add. Unassigned, every add route answers a named 503.
        srv.ports.add = AddWiring.console(log)

        // V4-220 item 4: `splice upgrade` over POST /api/upgrade, run out of process. Unassigned, it and
        // GET /api/upgrade/run answer a named 503.
        val upgradeRuns = LifecycleWiring.consoleUpgrades()
        srv.ports.lifecycle.upgradeRuns = upgradeRuns
        // Auto-update (Oct 13): the same runs, started by the daemon itself while splice.toml says auto_update = true.
        topology.path?.let { LifecycleWiring.autoUpdate(upgradeRuns, it, log).start() }
    }

    /** V4-133 (FEATURES.md §5/§6): the console's budget/alert stores and playground probe, split out
     *  of ControlPlane.start() (LongMethod, the same reason these ports moved here in V4-161) — the
     *  same settable-after-construction shape [wire] uses for its four, assigned right after it.
     *  Deviation from V4-133's "never recorded" (Oct 12, 2026, marlin): the playground probe now records its sends and
     *  takes the command's budget; docs/specs/v0.4.0.md section 9. */
    internal fun wireV4133(srv: ControlServer, budgets: BudgetStore, alerts: AlertStore, playground: PlaygroundProbe) {
        srv.ports.budgets = budgets
        srv.ports.alerts = alerts
        srv.ports.playground = playground
    }

    /** V4-239: what `splice models`, `splice trace` and `splice wire` print, as the console reads it.
     *  The comparison reads the file this daemon booted from (none booted, none to compare: the route
     *  answers its named 503); the trace dir is the one every head's TraceStore writes under
     *  [statePaths]; the wire taps are [console]'s, the registry HeadServerFactory fills as it builds
     *  each head, so the route and the heads cannot hold different registries. V4-319: the live turns
     *  the same way, so the stop reaches the turn the head is running. */
    internal fun wireVerbReads(
        srv: ControlServer,
        topology: BootedTopology,
        statePaths: StatePaths,
        console: ConsoleEventPublisher,
    ) {
        srv.ports.localRuntimes = topology.path?.let { ModelsWiring.localRuntimes(it) }
        srv.ports.traceDir = statePaths.traceDir
        srv.ports.turnStatistics = statePaths
        srv.ports.wires = console.wires
        srv.ports.liveTurns = console.liveTurns
    }

    /** V4-130: the daemon's ONE pair of activity stores, under the state dir's activity directory. The edge window and
     *  the edge switch are read live from the config (S9 #13), so a change on Settings reaches the next write; the
     *  store-heads list is read once and stays restart-only. A retention below one day would keep nothing, including
     *  today, so it is read as one. */
    internal fun activityStores(statePaths: StatePaths, config: ConfigService): ActivityStores {
        val knobs = config.getConfig().asMap()
        val days = (knobs[Knob.ACTIVITY_RETENTION_DAYS.key] as? Long ?: Knob.ACTIVITY_RETENTION_DAYS.count())
        val heads = knobs[Knob.ACTIVITY_STORE_HEADS.key] as? String ?: ALL_HEADS
        val edges = knobs[Knob.MESSAGE_EDGES.key] as? Boolean ?: true
        return ActivityStores(
            statePaths.stateDir.resolve(ACTIVITY_DIRECTORY),
            days.coerceAtLeast(1L).toInt(),
            heads,
            messageEdges = edges,
            heap = JvmHeap.budget,
            live = LiveEdges(
                RetentionDays { config.getConfig().activityRetentionDays },
                EdgesStored { config.getConfig().messageEdges },
            ),
        )
    }

    /** V4-131: the daemon's ONE team store, `teams.json` under the state dir. */
    internal fun teamStore(statePaths: StatePaths): TeamStore = TeamStore(statePaths.stateDir.resolve(TEAMS_FILE))

    /** V4-133: the daemon's ONE budget store, `budgets.json` under the state dir. */
    internal fun budgetStore(statePaths: StatePaths): BudgetStore =
        BudgetStore(statePaths.stateDir.resolve(BUDGETS_FILE))

    /** V4-133: the daemon's ONE alert-settings store, `alerts.json` under the state dir. */
    internal fun alertStore(statePaths: StatePaths): AlertStore =
        AlertStore(statePaths.stateDir.resolve(ALERTS_FILE))

    /** V4-133 review: the daemon's ONE budget enforcement, over the SAME stores the budget and alert
     *  routes edit, so a PUT is enforced on the next turn with no restart. A `warn` alert is posted
     *  on [scope] (the control plane's own, cancelled at stop); a budgeted head's spend from before
     *  this boot is read from its perf files, archive included. */
    internal fun budgetEnforcement(
        history: HeadPerfHistory,
        budgets: BudgetStore,
        alerts: AlertStore,
        scope: CoroutineScope,
        log: LogSink,
    ): BudgetEnforcement = BudgetEnforcement(
        budgets,
        AlertDelivery(alerts, log, scope),
        history,
        log,
        seed = splice.usage.budgets.BudgetSeedRuntime(scope),
    )

    internal fun sessionAddress(statePaths: StatePaths): SessionAddress =
        ConsoleSessionWiring.sessionAddress(statePaths)

    internal fun nameHolders(statePaths: StatePaths): NameHolders = ConsoleSessionWiring.nameHolders(statePaths)

    internal fun sessionsByPid(statePaths: StatePaths): McpSessionsByPid =
        ConsoleSessionWiring.sessionsByPid(statePaths)
}
