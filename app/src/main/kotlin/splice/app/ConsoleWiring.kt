// Console route adapters and stores, assembled after the control server is constructed.
package splice.app

import kotlinx.coroutines.CoroutineScope
import splice.app.control.ConsolePortsWiring
import splice.app.control.ControlServer
import splice.app.daemon.BootedTopology
import splice.app.sources.mcp.McpSessionsByPid
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.model.HeadDiscoveredModels
import splice.core.storage.ACTIVITY_DIRECTORY
import splice.core.storage.RetentionDays
import splice.core.util.LogSink
import splice.diagnostics.playground.PlaygroundProbe
import splice.sessions.activity.ALL_HEADS
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.EdgesStored
import splice.sessions.activity.LiveEdges
import splice.sessions.activity.NameHolders
import splice.sessions.prompt.SessionAddress
import splice.sessions.teams.TEAMS_FILE
import splice.sessions.teams.TeamStore
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
    /** Stable control-plane entry point; the route-port assembly lives in [ConsolePortsWiring]. */
    internal fun wire(
        srv: ControlServer,
        topology: BootedTopology,
        discovered: HeadDiscoveredModels,
        configBackups: Path,
        log: LogSink,
    ): Unit = ConsolePortsWiring.wire(srv, topology, discovered, configBackups, log)

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
        srv.ports.turns.live = console.liveTurns
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
    internal fun teamStore(statePaths: StatePaths, log: LogSink = LogSink { }): TeamStore =
        TeamStore(statePaths.stateDir.resolve(TEAMS_FILE), log = log)

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
