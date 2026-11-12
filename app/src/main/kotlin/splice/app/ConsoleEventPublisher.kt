// The heads report activity through this shared publisher; console routes read its stores.
package splice.app

import splice.core.session.ActivityAction
import splice.core.util.WallClock
import splice.head.HeadEvents
import splice.head.turn.LiveTurnsByHead
import splice.head.wire.WireTaps
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.activity.NameHolders
import splice.sessions.activity.RecipientResolution
import splice.sessions.prompt.SlotInstructions
import splice.sessions.registry.ForegroundTools
import splice.sessions.registry.HeadOfSession
import splice.sessions.registry.SessionsHeard
import splice.usage.budgets.BudgetEnforcement

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
    /** 2026-10-19: the tools a session is inside, from the wire: a turn that ends clean on tool calls opens them
     *  and the next request answers them, so a session inside one long tool stays heard without any client hook.
     *  Null (tests, tools) tracks none. */
    private val foreground: ForegroundTools? = null,
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
        /** Remembers which head [session]'s turn arrived on, for the registry's heard-from time and the listing's
         *  head join. */
        override fun turnStarted(session: String?) {
            if (session != null) started(session)
        }

        override fun turnEnded(perfRowId: String, outcome: String, session: String?) {
            if (session != null) ended(session)
        }

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

        override fun toolsOpened(session: String, toolUseIds: List<String>) {
            foreground?.opened(session, toolUseIds)
        }

        override fun toolsClosed(session: String, toolUseIds: List<String>) {
            foreground?.answered(session, toolUseIds)
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
