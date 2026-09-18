// NEW: V4-156 — the console's post-construction ports, moved out of ControlPlane.start(). They
// carried ControlPlane's only imports of splice.app.cli and splice.app.console, and ControlPlane was
// band HIGH on concerns (distinct subsystems imported); the lines are unchanged, only their home is.
// ControlPlane calls [ConsoleWiring.wire] immediately after constructing the ControlServer, where the
// lines used to sit. The V4-127/V4-137 wiring pins moved with them (ConsoleWiringPinTest), and a new
// pin there asserts that ControlPlane still makes this call, since deleting the call would unwire
// all four ports at once without breaking the build.
//
// V4-134, FEATURES.md §6 — ConsoleEventPublisher, below: the daemon's ONE console event bus and
// the adapter every head reports through. :gateway reports what a head observed (HeadEvents); this turns it into the
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
//   message.edge    NOT PRODUCED. The wire observation of SendMessage is V4-130's; it publishes
//                   through this class when it lands. Until then the family is silent by design.
//
// WHY IN THIS FILE: the publisher is the console's wiring seam for /api/events, the same job the
// four ports above do for their routes, so it lives beside them rather than in a package of its own.
package splice.app

import splice.app.cli.DoctorCommand
import splice.app.console.ConsoleUpgradeStatus
import splice.app.console.DrainingRestartAdapter
import splice.control.ControlServer
import splice.control.DoctorReport
import splice.control.UpgradeStatus
import splice.control.api.ConsoleEvent
import splice.control.api.EventBus
import splice.gateway.head.HeadEvents
import splice.gateway.head.HeadLifecycle

internal object ConsoleWiring {
    internal fun wire(srv: ControlServer, topology: BootedTopology) {
        // V4-127: the console's three read ports, assigned after construction because a constructor
        // parameter would widen ControlServer past the width ratchet — so the compiler cannot check
        // any of these lines, and deleting one does not break the build. Each route then answers its
        // NAMED 5xx, and the upgrade one is the worst of them: an unwired port there reads as a
        // measured payload saying nothing is newer, which tells an operator they are up to date when
        // nobody has ever looked. The deletion pins in ConsoleWiringPinTest are what make that a red
        // instead of a quiet lie.
        srv.declaredHeads = topology.declaredHeads
        srv.doctor = DoctorReport(DoctorCommand()::reportJson)
        srv.upgrade = UpgradeStatus(ConsoleUpgradeStatus()::json)
        // V4-137: the draining restart's supervision probe. Unlike the three above, leaving this one
        // unassigned is SAFE BY CONSTRUCTION — ControlServer.supervised is null until set and the
        // route refuses on null, so an unwired port declines to drain rather than draining a daemon
        // nothing would restart. It is assigned here anyway because the refusal is not the answer we
        // want on a host where systemd does run the daemon, and pinned for the same reason the others
        // are: the compiler cannot see this line either.
        srv.supervised = DrainingRestartAdapter()
    }
}

/** How many sessions [ConsoleEventPublisher] remembers the head of. A session past this many
 *  more-recent ones is forgotten, and its next turn reads as a first sight again: one extra
 *  session.change for a session that did not move, never a missed move. Sized well above the
 *  sessions one daemon serves at once. */
private const val REMEMBERED_SESSIONS = 4096

internal class ConsoleEventPublisher {
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

        /** Records [session] on this head and says whether that is news. */
        private fun moved(session: String): Boolean = synchronized(sessionHeads) {
            val previous = sessionHeads.remove(session)
            sessionHeads[session] = head
            if (sessionHeads.size > REMEMBERED_SESSIONS) sessionHeads.remove(sessionHeads.keys.first())
            previous != head
        }
    }
}
