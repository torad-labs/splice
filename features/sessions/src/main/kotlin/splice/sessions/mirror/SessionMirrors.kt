// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The sessions of the machines this one shares with,
// made Claude Code peers here, and this machine's own sessions as another machine asks for them. The share link says
// which sessions each machine has (mirror), and carries a message one way (the carrier a mirror hands it to) and the
// other (deliver, to a session running here, from the mirror of its sender, so a reply finds its way back). A mirror
// is never offered on: this machine's sessions are the ones Claude Code itself registered. Any machine on the network
// may join, and each mirror is a process, so one daemon hosts at most [MIRRORS_AT_MOST] of them. A machine's mirrors
// end here, whatever made them, once their time is past: a look or an offer from it moves that time later, and a note
// gives one only to a machine that has none. Two running records under one session id, as `claude --resume` leaves
// while the first process still runs, are one session here, the one heard from last (round 13 of PR #419's review).
// Since round 17 a session is mirrored once, whichever ways name it: a machine reaches this one by this machine's look
// at it and by its own offers and notes here, and one this machine keys twice (`b` and `b-2`) names its sessions
// under both keys. What each way names is a claim with its own time. A mirror stays while any claim names its session,
// under the machine it is listed under while that machine still names it, and hands a message to that machine's way
// that named it last, so one way going quiet leaves what another still names as it is.
package splice.sessions.mirror

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import splice.core.util.Cancellables
import splice.core.util.LogSafe
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.util.WallClock
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerTargets
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.helper.HelperLog
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.registry.MIRROR_KIND
import splice.sessions.registry.PidIdentity
import splice.sessions.registry.ProcPidIdentity
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.nio.file.Path
import java.util.concurrent.Executors

/** Where this machine's Claude Code keeps its sessions (its registry folder and the folder of its inbox sockets), and
 *  the log the mirrors' inboxes count what they saw in. */
public data class MirrorPlace(val registry: Path, val sockets: Path, val log: Path)

/** What holds a mirror's pid: a process of its own, started for each mirror. Throws IOException when none can start,
 *  as a fork refused for want of memory or processes. */
public fun interface MirrorAnchor {
    public fun start(): Process
}

/** The anchor every mirror holds in the daemon: `/bin/cat` reading a pipe from the daemon, which exits at its end. */
internal object CatAnchor : MirrorAnchor {
    override fun start(): Process = ProcessBuilder(ANCHOR)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

/** A session on another machine: its id there, the name it goes by there, and when it was last heard from there, by
 *  that machine's clock (null when its record says nothing of it). */
public data class MirroredSession(val id: String, val name: String, val heardAt: Long? = null)

/** How sessions cross with one machine: how many of its sessions are mirrored here, and how many of this machine's it
 *  was last shown (null until it is, and again once every way it reaches this one goes quiet). */
public data class MirrorCounts(val here: Int, val there: Int?)

/** What every mirror serves with: the folders, the registry's files and reader, the outbox a message and a receipt go
 *  out through, the log of its inbox, and the scope its inbox is served in, on [inboxes]: each inbox waits in a
 *  blocking accept, so on [io], the daemon's one pool for blocking work, 64 mirrors would hold every thread it has and
 *  stall the daemon. A virtual thread waiting in a channel's accept holds none. */
internal data class MirrorParts(
    val place: MirrorPlace,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val identity: PidIdentity,
    val user: String,
    val anchor: MirrorAnchor,
) {
    val files = PeerRegistryFiles(listOf(place.registry))
    val sockets = PeerSockets(null, null, user)
    val registry = SessionRegistry(place.registry, { SessionRoute.Unknown }, identity = identity)
    val targets = PeerTargets(registry)
    val outbox = PeerNoteSocket(io, user = user, registryDirs = listOf(place.registry))
    val helperLog = HelperLog(place.log, WallClock { System.currentTimeMillis() })
    val inboxes: ExecutorCoroutineDispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    /** This machine's own running sessions by id, mirrors left out: of two records under one id, as `claude --resume`
     *  leaves while the first process still runs, the one heard from last. */
    fun ownSessions(): Map<String, OwnSession> = registry.read()
        .filter { it.availability != SessionAvailability.GONE && it.client.kind != MIRROR_KIND }
        .mapNotNull { record ->
            val heardAt = listOfNotNull(record.process.updatedAt, record.status.updatedAt).maxOrNull()
            record.sessionId?.let { id -> id to OwnSession(record, heardAt) }
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, records) -> records.maxBy { it.heardAt ?: Long.MIN_VALUE } }
}

/** The mirrors this daemon hosts at [place], their inboxes served in [scope] on [io], each holding a pid by [anchor].
 *  What each way named is withdrawn once its time by [clock] is past, looked for at each turn of [sweep]. */
public class SessionMirrors(
    place: MirrorPlace,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val log: LogSink,
    sweep: MirrorSweep,
    private val clock: WallClock = WallClock { System.currentTimeMillis() },
    anchor: MirrorAnchor = CatAnchor,
) : AutoCloseable {
    private val parts =
        MirrorParts(place, scope, io, ProcPidIdentity(), System.getProperty("user.name").orEmpty(), anchor)
    private val lock = Any()

    /** What each way a machine's sessions reach this one by names now: the sessions it looked, offered or wrote
     *  from. */
    private val claims = MirrorClaims()

    /** The mirrors by the id of the session each stands for: one a session, whichever ways name it. */
    private val mirrors = mutableMapOf<String, Mirror>()

    /** How many of this machine's sessions each machine was last shown. */
    private val shownTo = mutableMapOf<String, Int>()

    /** The machines that offered more sessions than there was room for, said once until they fit again. */
    private val overfull = mutableSetOf<String>()

    /** Whether the daemon withdrew every mirror as it stops: an offer or a note still on its way then makes none, as
     *  nothing would sweep it once the daemon is gone and its record would outlive it. */
    private var closed = false

    init {
        scope.launch(io) {
            while (sweep.awaitNext(MIRRORS_SWEEP_MS)) expire()
        }
    }

    /** This machine's own running sessions, as another machine is shown them: every one Claude Code registered here
     *  whose process runs, mirrors left out, once each. */
    public fun local(): List<MirroredSession> = parts.ownSessions().map { (id, own) ->
        MirroredSession(id, own.record.name ?: id.take(SHORT_ID_CHARS), own.heardAt)
    }

    /** How sessions cross with [machine], as the Sharing page shows it: of the sessions its ways name, how many are
     *  mirrored here, under whichever key. */
    public fun counts(machine: String): MirrorCounts = synchronized(lock) {
        MirrorCounts(claims.named(machine).count { it in mirrors }, shownTo[machine])
    }

    /** [machine] was just shown [sessions] of this machine's running sessions. */
    public fun shown(machine: String, sessions: Int) {
        synchronized(lock) { shownTo[machine] = sessions }
    }

    /** [way] of [machine] went quiet (a look that failed, its Sessions switch off, its offers ending): what it named
     *  stays mirrored while another way names it, and [machine] is forgotten once none does, so nothing crosses with
     *  it until it looks or offers again. */
    public fun withdraw(machine: String, way: MirrorWay) {
        synchronized(lock) {
            claims.drop(machine, way)
            settle(setOf(machine))
        }
    }

    /** Withdraws each way whose time is past. */
    public fun expire() {
        synchronized(lock) {
            val ended = claims.expire(clock())
            if (ended.isNotEmpty()) settle(ended)
        }
    }

    /** Forgets each of [machines] no way names any more, and makes the mirrors what the ways name now. */
    private fun settle(machines: Set<String>) {
        for (machine in machines - claims.machines()) {
            shownTo.remove(machine)
            overfull.remove(machine)
        }
        reconcile()
    }

    /** Makes [sessions] what [machine] names by [link]'s way: each one no way named before registered, each one no
     *  way names any more withdrawn, a renamed one registered anew under its new name. A message to one goes by the
     *  carrier of its machine's way that named it last, and what [link]'s way names lasts at least [link]'s time from
     *  now, those made before a failure part way included. None once the daemon closed them. */
    public fun mirror(machine: String, sessions: List<MirroredSession>, link: MirrorLink) {
        synchronized(lock) {
            if (closed) return
            claims.name(machine, sessions, link, clock())
            reconcile()
        }
    }

    /** Makes the mirrors what the ways name, and answers why each session named that has no mirror could not have
     *  one. */
    private fun reconcile(): Map<String, String> {
        val wanted = claims.wanted(mirrors)
        mirrors.entries.removeAll { (id, mirror) ->
            val stale = wanted[id]?.keeps(mirror) != true
            if (stale) mirror.close()
            stale
        }
        for ((id, mirror) in mirrors) {
            val want = wanted.getValue(id)
            mirror.heard(want.session)
            mirror.carrier = want.carrier
        }
        return opened(wanted.values.filter { it.session.id !in mirrors })
    }

    /** Opens a mirror for each of [fresh] there is room for, and says, once, of each machine whose sessions did not
     *  all fit. Answers why each that has none could not have one. */
    private fun opened(fresh: List<MirrorWant>): Map<String, String> {
        val room = (MIRRORS_AT_MOST - mirrors.size).coerceAtLeast(0)
        val refused = fresh.drop(room).associateTo(mutableMapOf()) { want ->
            want.session.id to "this machine already hosts the most mirrors it can ($MIRRORS_AT_MOST), so " +
                "${want.session.name} of ${want.machine} has none here"
        }
        for (want in fresh.take(room)) {
            val (mirror, why) = open(want.machine, want.session, want.carrier)
            if (mirror != null) {
                mirrors[want.session.id] = mirror
            } else {
                refused[want.session.id] = "${want.session.name} of ${want.machine} cannot be mirrored here: $why"
            }
        }
        val left = fresh.drop(room).groupingBy { it.machine }.eachCount()
        claims.machines().forEach { roomFor(it, left[it] ?: 0) }
        return refused
    }

    /** Delivers [text] from [from], a session on [machine], to the session here whose id is [to]. It goes from the
     *  mirror of [from], made now with [link] when there is none yet, so a reply to it travels back. */
    public suspend fun deliver(
        to: String,
        machine: String,
        from: MirroredSession,
        text: String,
        link: MirrorLink,
    ): MirrorCarried {
        val target = parts.ownSessions()[to]?.record
            ?: return MirrorCarried(DeliveryStatus.REFUSED, "no session here has the id $to")
        val (sender, refused) = senderOf(machine, from, link)
        sender ?: return MirrorCarried(DeliveryStatus.REFUSED, refused)
        return when (val sent = parts.outbox.send(target, text, sender.from)) {
            is SendOutcome.Submitted -> MirrorCarried(DeliveryStatus.DELIVERED)
            is SendOutcome.Refused -> MirrorCarried(DeliveryStatus.REFUSED, sent.reason)
            is SendOutcome.Failed -> MirrorCarried(DeliveryStatus.DROPPED, sent.reason)
        }
    }

    /** Withdraws every mirror, and makes none from now on. */
    override fun close() {
        synchronized(lock) {
            closed = true
            mirrors.values.forEach(Mirror::close)
            mirrors.clear()
            claims.clear()
        }
        parts.helperLog.flush()
        parts.inboxes.close()
    }

    /** The mirror here of [from], which [machine]'s way the note came by, [link]'s, now names: the one its session
     *  has, or one made now. Mirrors withdrawn since its last look, by a look that failed, its Sessions switch turned
     *  off and on, or its offers ending, take no note with them, so a way that names nothing takes [link]'s time and
     *  carrier, the ones the note came by. Null, with why, when it cannot be made. */
    private fun senderOf(machine: String, from: MirroredSession, link: MirrorLink): Pair<Mirror?, String?> =
        synchronized(lock) {
            if (closed) return@synchronized null to "this machine's splice is stopping"
            claims.add(machine, from, link, clock())
            val refused = reconcile()
            mirrors[from.id] to refused[from.id]
        }

    /** Says, once, that [machine] offered [left] sessions more than there was room for. */
    private fun roomFor(machine: String, left: Int) {
        if (left <= 0) {
            overfull.remove(machine)
        } else if (overfull.add(machine)) {
            log(
                "share: ${LogSafe.str(left.toString())} sessions of ${LogSafe.str(machine)} are not mirrored: this " +
                    "daemon hosts at most ${LogSafe.str(MIRRORS_AT_MOST.toString())}",
            )
        }
    }

    /** A mirror of [session], open, or null with why it could not be, which the log says too: one whose process
     *  could not start, as a fork refused for want of memory or processes, is refused like a socket that would not
     *  bind. */
    private fun open(machine: String, session: MirroredSession, carrier: MirrorCarrier): Pair<Mirror?, String?> {
        val opened: Pair<Mirror?, String?> =
            Cancellables.runCatchingCancellable { Mirror(machine, session, carrier, parts) }.fold(
                onSuccess = { made -> made to made.open() },
                onFailure = { null to "its process did not start: ${SafeFailureText.render(it)}" },
            )
        val refused = opened.second ?: return opened.first to null
        log(
            "share: ${LogSafe.str(session.name)} of ${LogSafe.str(machine)} cannot be mirrored here: " +
                LogSafe.str(refused),
        )
        return null to refused
    }
}

/** One of this machine's own sessions: its registry [record], and when it was last heard from, by its record. */
internal data class OwnSession(val record: SessionRecord, val heardAt: Long?)

// why: the anchor holds a pid and nothing else; cat reads its pipe from the daemon and exits at its end, which comes
// when the daemon exits, however it exits.
private const val ANCHOR = "/bin/cat"

// why: an unnamed session is shown by the start of its id, as Claude Code shows one.
internal const val SHORT_ID_CHARS = 8

// why: each mirror is a process and a socket, and any machine on the network may join; 256 is several machines' worth
// of a busy day's sessions, and no machine can make this one start more.
private const val MIRRORS_AT_MOST = 256

// why: a machine whose time ran out is noticed within a second; its time is a second at the least.
private const val MIRRORS_SWEEP_MS = 1_000L
