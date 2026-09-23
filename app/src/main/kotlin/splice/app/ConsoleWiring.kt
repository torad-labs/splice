// NEW: V4-156 — the console's post-construction ports, moved out of ControlPlane.start(). They
// carried ControlPlane's only imports of splice.app.cli and splice.app.console, and ControlPlane was
// band HIGH on concerns (distinct subsystems imported); the lines are unchanged, only their home is.
// ControlPlane calls [ConsoleWiring.wire] immediately after constructing the ControlServer, where the
// lines used to sit. The V4-127/V4-137 wiring pins moved with them (ConsoleWiringPinTest), and a new
// pin there asserts that ControlPlane still makes this call, since deleting the call would unwire
// all four ports at once without breaking the build.
//
// V4-134, FEATURES.md §6 — ConsoleEventPublisher, below: the daemon's ONE console event bus and
// the adapter every head reports through. :daemon-head reports what a head observed (HeadEvents); this turns it into the
// ConsoleEvent families GET /api/events streams. ControlPlane holds the one instance: it hands
// [bus] to the ControlServer's route and [forHead] to every head HeadServerFactory builds, so the
// route and the producers cannot hold different buses. OneEventBusPinTest fails if they do.
//
// FAMILIES AND WHERE EACH COMES FROM:
//   head.state      HeadServer's lifecycle lock: started, draining, stopped.
//   turn.start      the ready turn's admission (HeadAdmission.serveReady), full session id.
//   turn.end        the one perf-row emitter (TurnTelemetry), keyed by that row's ts.
//   account.switch  the pool's switch, reported beside the perf row that carries the new account.
//   session.change  derived HERE from turn.start — see [HeadPublisher.turnStarted].
//   message.edge    V4-130: the SendMessage tool_use a head observed on the wire (MessageEdges).
//                   Published AND recorded in the edges store, so the per-session edges route and
//                   the live stream carry the same facts.
//
// V4-130 STORES: [ConsoleEventPublisher.stores] holds the edges and activity-label stores. Null (tests,
// tools) means the bus still carries every family and nothing is written to disk; ControlPlane opens
// the daemon's one instance under the state dir, so no test writes into the operator's state dir.
// A label or an upstream label query without a session header is published nowhere and stored
// nowhere: a label row is keyed by its session, and one without a session answers no console read.
//
// WHY IN THIS FILE: the publisher is the console's wiring seam for /api/events, the same job the
// four ports above do for their routes, so it lives beside them rather than in a package of its own.
package splice.app

import splice.app.auth.ConsoleAccountsImpl
import splice.app.cli.doctor.DoctorCommand
import splice.app.console.ConsoleUpgradeStatus
import splice.app.console.DrainingRestartAdapter
import splice.app.daemon.BootedTopology
import splice.app.daemon.TopologyLoader
import splice.control.ControlServer
import splice.control.DoctorReport
import splice.control.UpgradeStatus
import splice.control.api.ConsoleEvent
import splice.control.api.EventBus
import splice.control.api.turns.PlaygroundProbe
import splice.core.config.ConfigService
import splice.core.config.Knob
import splice.core.config.StatePaths
import splice.core.model.HeadDiscoveredModels
import splice.core.storage.ACTIVITY_DIRECTORY
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.core.util.WallClock
import splice.head.HeadEvents
import splice.head.HeadLifecycle
import splice.sessions.activity.ALL_HEADS
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.prompt.SessionAddress
import splice.sessions.prompt.SlotInstructions
import splice.sessions.registry.HeadOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.teams.TEAMS_FILE
import splice.sessions.teams.TeamStore
import splice.usage.alerts.ALERTS_FILE
import splice.usage.alerts.AlertStore
import splice.usage.budgets.BUDGETS_FILE
import splice.usage.budgets.BudgetStore

internal object ConsoleWiring {
    internal fun wire(srv: ControlServer, topology: BootedTopology, discovered: HeadDiscoveredModels) {
        // V4-127: the console's three read ports, assigned after construction because a constructor
        // parameter would widen ControlServer past the width ratchet — so the compiler cannot check
        // any of these lines, and deleting one does not break the build. Each route then answers its
        // NAMED 5xx, and the upgrade one is the worst of them: an unwired port there reads as a
        // measured payload saying nothing is newer, which tells an operator they are up to date when
        // nobody has ever looked. The deletion pins in ConsoleWiringPinTest are what make that a red
        // instead of a quiet lie.
        srv.ports.declaredHeads = topology.declaredHeads
        srv.ports.doctor = DoctorReport(DoctorCommand()::reportJson)
        srv.ports.upgrade = UpgradeStatus(ConsoleUpgradeStatus()::json)
        // V4-137: the draining restart's supervision probe. Unlike the three above, leaving this one
        // unassigned is SAFE BY CONSTRUCTION — ConsolePorts.supervised is null until set and the
        // route refuses on null, so an unwired port declines to drain rather than draining a daemon
        // nothing would restart. It is assigned here anyway because the refusal is not the answer we
        // want on a host where systemd does run the daemon, and pinned for the same reason the others
        // are: the compiler cannot see this line either.
        srv.ports.supervised = DrainingRestartAdapter()

        // V4-128: the writer is the ONLY seam that edits splice.toml, and it is built here because
        // this is the one place that knows the booted file's path. A null path is a daemon booted
        // without a config file: the route declines rather than writing a file nobody asked for.
        srv.ports.topology = topology.path?.let {
            TopologyWriter(it, TopologyParse(TopologyLoader::parse), discovered = discovered)
        }

        // V4-132: the login/remove/relabel machinery — :daemon-control depends on :core only, so this is
        // the port's :app-side implementation, assigned here like every port above it. Unassigned,
        // the four routes behind it answer a named 503 rather than a payload reading as "no
        // accounts".
        srv.ports.accounts = ConsoleAccountsImpl()
    }

    /** V4-133 (FEATURES.md §5/§6): the console's budget/alert stores and playground probe, split out
     *  of ControlPlane.start() (LongMethod, the same reason these ports moved here in V4-161) — the
     *  same settable-after-construction shape [wire] uses for its four, assigned right after it. */
    internal fun wireV4133(srv: ControlServer, budgets: BudgetStore, alerts: AlertStore, playground: PlaygroundProbe) {
        srv.ports.budgets = budgets
        srv.ports.alerts = alerts
        srv.ports.playground = playground
    }

    /** V4-130: the daemon's ONE pair of activity stores, under the state dir's activity directory, with
     *  the two knobs read once (both restartRequired). A retention below one day would keep nothing,
     *  including today, so it is read as one. */
    internal fun activityStores(statePaths: StatePaths, config: ConfigService): ActivityStores {
        val knobs = config.getConfig().asMap()
        val days = (knobs[Knob.ACTIVITY_RETENTION_DAYS.key] as? Long ?: Knob.ACTIVITY_RETENTION_DAYS.default as Long)
        val heads = knobs[Knob.ACTIVITY_STORE_HEADS.key] as? String ?: ALL_HEADS
        return ActivityStores(statePaths.stateDir.resolve(ACTIVITY_DIRECTORY), days.coerceAtLeast(1L).toInt(), heads)
    }

    /** V4-131: the daemon's ONE team store, `teams.json` under the state dir. */
    internal fun teamStore(statePaths: StatePaths): TeamStore = TeamStore(statePaths.stateDir.resolve(TEAMS_FILE))

    /** V4-133: the daemon's ONE budget store, `budgets.json` under the state dir. */
    internal fun budgetStore(statePaths: StatePaths): BudgetStore =
        BudgetStore(statePaths.stateDir.resolve(BUDGETS_FILE))

    /** V4-133: the daemon's ONE alert-settings store, `alerts.json` under the state dir. */
    internal fun alertStore(statePaths: StatePaths): AlertStore =
        AlertStore(statePaths.stateDir.resolve(ALERTS_FILE))

    /** V4-131: a session id to its SendMessage address, from the same registry /api/sessions reads (the
     *  home the state dir lives under, as ControlPlane.start derives it), for the slot text's lead line. */
    internal fun sessionAddress(statePaths: StatePaths): SessionAddress {
        val home = statePaths.rootDir.parent ?: statePaths.rootDir
        val registry = SessionRegistry(home.resolve(".claude").resolve("sessions"), HeadOfPid { null })
        return SessionAddress { session -> registry.read().firstOrNull { it.sessionId == session }?.address }
    }
}

/** How many sessions [ConsoleEventPublisher] remembers the head of. A session past this many
 *  more-recent ones is forgotten, and its next turn reads as a first sight again: one extra
 *  session.change for a session that did not move, never a missed move. Sized well above the
 *  sessions one daemon serves at once. */
private const val REMEMBERED_SESSIONS = 4096

internal class ConsoleEventPublisher(
    internal val stores: ActivityStores? = null,
    private val clock: WallClock = WallClock(System::currentTimeMillis),
    /** V4-131: the team-slot resolver every head appends from. Carried here, beside the stores, because
     *  this is the one console object HeadServerFactory already receives for every head. */
    internal val slots: SlotInstructions? = null,
) {
    /** The bus GET /api/events streams from. ControlPlane assigns this exact instance to the
     *  ControlServer; nothing else constructs one for production. */
    internal val bus: EventBus = EventBus()

    /** Session id -> the head its latest turn ran on, in least-recently-used order. */
    private val sessionHeads = LinkedHashMap<String, String>()

    /** The reporter for one head. Keyed here so a head can never report under another's name. */
    internal fun forHead(head: String): HeadEvents = HeadPublisher(head)

    private inner class HeadPublisher(private val head: String) : HeadEvents {
        override fun lifecycle(state: HeadLifecycle) {
            bus.publish { seq -> ConsoleEvent.HeadState(seq, head, state.wire) }
        }

        /** Also the source of session.change, which reports a session APPEARING or MOVING HEAD:
         *  the first turn of a session this daemon has not seen, or a turn on a different head than
         *  that session's last one.
         *
         *  A RETIRED SESSION CLEARS ON THE NEXT session.change, NOT ON ITS OWN RETIREMENT. The event
         *  is a doorbell: the console binds its sessions to a refetch of the poll route, not to this
         *  payload, so any session.change re-reads the registry and drops what has ended. Retirement
         *  itself rings nothing, because the registry is Claude Code's own files, which splice only
         *  reads, and the only fact on the turn path is that a session turned up. */
        override fun turnStarted(session: String?) {
            bus.publish { seq -> ConsoleEvent.TurnStart(seq, head, session) }
            if (session != null && moved(session)) {
                bus.publish { seq -> ConsoleEvent.SessionChange(seq, session, head) }
            }
        }

        override fun turnEnded(perfRowId: String, outcome: String) {
            bus.publish { seq -> ConsoleEvent.TurnEnd(seq, head, perfRowId, outcome) }
        }

        override fun accountSwitched(from: String?, to: String) {
            bus.publish { seq -> ConsoleEvent.AccountSwitched(seq, head, from, to) }
        }

        override fun messageSent(session: String, to: String, toolUseId: String) {
            val at = clock()
            stores?.edges?.record(MessageEdge(session, to, at, toolUseId))
            bus.publish { seq -> ConsoleEvent.EdgeEvent(seq, session, to, at) }
        }

        override fun activityLabel(session: String?, label: String) {
            if (session != null) stores?.activity?.label(session, head, label, clock())
        }

        override fun labelQueryUpstream(session: String?) {
            if (session != null) stores?.activity?.upstream(session, head, clock())
        }

        /** Records [session] on this head and says whether that is news. */
        private fun moved(session: String): Boolean = synchronized(sessionHeads) {
            val previous = sessionHeads.remove(session)
            sessionHeads[session] = head
            if (sessionHeads.size > REMEMBERED_SESSIONS) sessionHeads.remove(sessionHeads.keys.first())
            previous != head
        }
    }
}
