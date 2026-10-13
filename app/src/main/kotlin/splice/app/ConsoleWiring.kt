// NEW: V4-156 — the console's post-construction ports, moved out of ControlPlane.start(). They
// carried ControlPlane's only imports of splice.app.cli and splice.app.console, and ControlPlane was
// band HIGH on concerns (distinct subsystems imported); the lines are unchanged, only their home is.
// ControlPlane calls [ConsoleWiring.wire] immediately after constructing the ControlServer, where the
// lines used to sit. The V4-127/V4-137 wiring pins moved with them (ConsoleWiringPinTest), and a new
// pin there asserts that ControlPlane still makes this call, since deleting the call would unwire
// all four ports at once without breaking the build.
//
// ConsoleEventPublisher, below: the adapter every head reports through. :daemon-head reports what a head observed
// (HeadEvents); this records what the console reads of it. ControlPlane holds the one instance and hands [forHead] to
// every head HeadServerFactory builds. Oct 12, 2026: the live stream at GET /api/events, which no console page, CLI
// or tool ever opened, is gone, and with it the bus this used to publish every head's facts onto.
//
// V4-130 STORES: [ConsoleEventPublisher.stores] holds the edges and activity-label stores. Null (tests, tools) means
// nothing is written to disk; ControlPlane opens the daemon's one instance under the state dir, so no test writes into
// the operator's state dir. A label or an upstream label query without a session header is stored nowhere: a label
// row is keyed by its session, and one without a session answers no console read.
//
// WHY IN THIS FILE: the publisher is the console's wiring seam for the heads, the same job the four ports above do
// for their routes, so it lives beside them rather than in a package of its own.
package splice.app

import kotlinx.coroutines.CoroutineScope
import splice.app.auth.ConsoleAccountsImpl
import splice.app.console.DrainingRestartAdapter
import splice.app.control.ControlServer
import splice.app.daemon.BootedTopology
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.model.HeadDiscoveredModels
import splice.core.session.ActivityAction
import splice.core.storage.ACTIVITY_DIRECTORY
import splice.core.storage.RetentionDays
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.util.EnvReader
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.diagnostics.doctor.DoctorReport
import splice.diagnostics.playground.PlaygroundProbe
import splice.head.HeadEvents
import splice.head.HeadLifecycle
import splice.head.turn.LiveTurnsByHead
import splice.head.wire.WireTaps
import splice.lifecycle.upgrade.ConsoleUpgradeStatus
import splice.lifecycle.upgrade.UpgradeChecks
import splice.lifecycle.upgrade.UpgradeStatus
import splice.sessions.activity.ALL_HEADS
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.EdgesStored
import splice.sessions.activity.LiveEdges
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.NameHolders
import splice.sessions.activity.RecipientResolution
import splice.sessions.prompt.SessionAddress
import splice.sessions.prompt.SlotInstructions
import splice.sessions.registry.HeadOfSession
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionsHeard
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
        val upgradeChecks = UpgradeChecks(EnvReader(System::getenv))
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
        srv.ports.lifecycle.upgradeRuns = LifecycleWiring.consoleUpgrades()
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

    /** V4-131: a session id to its SendMessage address, from the same registry /api/sessions reads (the
     *  home the state dir lives under, as ControlPlane.start derives it), for the slot text's lead line. */
    internal fun sessionAddress(statePaths: StatePaths): SessionAddress {
        val registry = sessionRegistry(statePaths)
        return SessionAddress { session -> registry.read().firstOrNull { it.sessionId == session }?.address }
    }

    /** V4-252: a SendMessage name to the session holding it, over the same registry, asked as its edge
     *  is stored. */
    internal fun nameHolders(statePaths: StatePaths): NameHolders = NameHolders(sessionRegistry(statePaths))

    private fun sessionRegistry(statePaths: StatePaths): SessionRegistry {
        val home = statePaths.rootDir.parent ?: statePaths.rootDir
        return SessionRegistry(
            home.resolve(UserHome.CLAUDE_DIR).resolve("sessions"),
            RouteOfPid { SessionRoute.Unknown },
        )
    }
}

/** How many sessions [ConsoleEventPublisher] remembers the head and latest turn of. A session past
 *  this many more-recent ones is forgotten until its next turn, and until then the registry hears from
 *  it only through its own file. Sized well above the sessions one daemon serves at once. */
private const val REMEMBERED_SESSIONS = 4096

internal class ConsoleEventPublisher(
    internal val stores: ActivityStores? = null,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** V4-131: the team-slot resolver every head appends from. Carried here, beside the stores, because
     *  this is the one console object HeadServerFactory already receives for every head. */
    internal val slots: SlotInstructions? = null,
    /** V4-133 review: the budgets the console saves, enforced. Each head's ledger comes from here for
     *  the same reason [slots] does. Null (tests, tools) builds every head with no budget. */
    internal val budgets: BudgetEnforcement? = null,
    /** V4-252: who holds a SendMessage name as its edge is stored, so the edge keeps the session that
     *  held it then. Null (tests, tools) stores every name with none. */
    internal val names: NameHolders? = null,
) {
    /** V4-239: every head's wire tap, registered by HeadServerFactory as it builds the head and read by
     *  GET /api/heads/{head}/wire. Here for the reason [slots] is: this is the one console object every
     *  head's factory already receives. */
    internal val wires: WireTaps = WireTaps()

    /** V4-319: every head's live turns, registered by HeadServerFactory as it builds the head and read
     *  by the console's live-turn routes, here for the reason [wires] is. */
    internal val liveTurns: LiveTurnsByHead = LiveTurnsByHead()

    /** Session id -> the head its latest turn ran on and when that turn last started or ended, in
     *  least-recently-used order. */
    private val sessionsSeen = LinkedHashMap<String, Seen>()

    /** V4-444: when this daemon last heard from each session, read by the daemon's session registry.
     *  Claude Code rewrites a registration only when its status changes, so a turn served is how a
     *  session busy for hours is heard from: now while a turn of it is live on any head, however long
     *  ago that turn started, else when its latest turn started or ended. A copy, so the registry never
     *  reads under the lock. */
    internal val sessionsHeard: SessionsHeard = SessionsHeard {
        val now = clock()
        val seen = synchronized(sessionsSeen) { sessionsSeen.mapValues { it.value.at } }
        seen + liveTurns.sessions().associateWith { now }
    }

    /** The head each session's latest turn arrived on, for the session listing's join of a session with no launch
     *  record. A copy under the lock, so the listing never reads under it. */
    internal val sessionHeads: HeadOfSession = HeadOfSession { id ->
        synchronized(sessionsSeen) { sessionsSeen[id]?.head }
    }

    private data class Seen(val head: String, val at: Long)

    /** The reporter for one head. Keyed here so a head can never report under another's name. */
    internal fun forHead(head: String): HeadEvents = HeadPublisher(head)

    private inner class HeadPublisher(private val head: String) : HeadEvents {
        /** Nothing reads a head's lifecycle or an account switch here since the live stream went (Oct 12). */
        override fun lifecycle(state: HeadLifecycle) = Unit

        /** Remembers which head [session]'s turn arrived on, for the registry's heard-from time and the listing's
         *  head join. */
        override fun turnStarted(session: String?) {
            if (session != null) started(session)
        }

        override fun turnEnded(perfRowId: String, outcome: String, session: String?) {
            if (session != null) ended(session)
        }

        override fun accountSwitched(from: String?, to: String) = Unit

        override fun messageSent(session: String, to: String, toolUseId: String) {
            val at = clock()
            // V4-411: an address is filed under the one live session that holds the socket, so the row
            // keeps its holder after the registry forgets it. None or several leave it as it was written
            // before the marker, which reads by the address; only a name says explicitly that nobody held it.
            val address = ':' in to
            val holder = if (address) names?.sessionAt(to) else names?.sessionOf(to)
            val recipient = when {
                holder != null -> RecipientResolution.Held(holder)
                address -> RecipientResolution.Legacy
                else -> RecipientResolution.NoHolder
            }
            stores?.edges?.record(MessageEdge(session, to, at, toolUseId, holder, recipient))
        }

        override fun activityLabel(session: String?, action: ActivityAction) {
            if (session != null) stores?.activity?.label(session, head, action, clock())
        }

        override fun labelQueryUpstream(session: String?) {
            if (session != null) stores?.activity?.upstream(session, head, clock())
        }

        /** Records [session]'s turn on this head, now. */
        private fun started(session: String): Unit = synchronized(sessionsSeen) {
            sessionsSeen.remove(session)
            seen(session, Seen(head, clock()))
        }

        /** Records that [session]'s turn ended now. The head it is remembered on stays: only a turn's
         *  start says which head a session moved to. */
        private fun ended(session: String): Unit = synchronized(sessionsSeen) {
            val previous = sessionsSeen.remove(session)
            seen(session, Seen(previous?.head ?: head, clock()))
        }

        /** Remembers [session] as the most recent, forgetting the least recent past the bound. */
        private fun seen(session: String, at: Seen) {
            sessionsSeen[session] = at
            if (sessionsSeen.size > REMEMBERED_SESSIONS) sessionsSeen.remove(sessionsSeen.keys.first())
        }
    }
}
