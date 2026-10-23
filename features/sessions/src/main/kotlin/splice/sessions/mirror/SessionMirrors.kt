// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). The sessions of the machines this one shares with,
// made Claude Code peers here, and this machine's own sessions as another machine asks for them. The share link says
// which sessions each machine has (mirror), and carries a message one way (the carrier a mirror hands it to) and the
// other (deliver, to a session running here, from the mirror of its sender, so a reply finds its way back). A mirror
// is never offered on: this machine's sessions are the ones Claude Code itself registered. Any machine on the network
// may join, and each mirror is a process, so one daemon hosts at most [MIRRORS_AT_MOST] of them.
package splice.sessions.mirror

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import splice.core.util.LogSafe
import splice.core.util.LogSink
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

/** Where this machine's Claude Code keeps its sessions (its registry folder and the folder of its inbox sockets), and
 *  the log the mirrors' inboxes count what they saw in. */
public data class MirrorPlace(val registry: Path, val sockets: Path, val log: Path)

/** A session on another machine: its id there, the name it goes by there, and when it was last heard from there, by
 *  that machine's clock (null when its record says nothing of it). */
public data class MirroredSession(val id: String, val name: String, val heardAt: Long? = null)

/** How sessions cross with one machine: how many of its sessions are mirrored here, and how many of this machine's it
 *  was last shown (null until it is, and again once its link goes quiet). */
public data class MirrorCounts(val here: Int, val there: Int?)

/** What became of a message a mirror carried on: its [status] there, or null when it is on its way. */
public data class MirrorCarried(val status: DeliveryStatus?, val detail: String? = null)

/** Carries a message a session here wrote to the mirror of [to] on to that session, from [from], the sender here. */
public fun interface MirrorCarrier {
    public suspend fun carry(to: MirroredSession, from: MirroredSession, text: String): MirrorCarried
}

/** What every mirror serves with: the folders, the registry's files and reader, the outbox a message and a receipt go
 *  out through, the log of its inbox, and the scope its inbox is served in. */
internal data class MirrorParts(
    val place: MirrorPlace,
    val scope: CoroutineScope,
    val io: CoroutineDispatcher,
    val identity: PidIdentity,
    val user: String,
) {
    val files = PeerRegistryFiles(listOf(place.registry))
    val sockets = PeerSockets(null, null, user)
    val registry = SessionRegistry(place.registry, { SessionRoute.Unknown }, identity = identity)
    val targets = PeerTargets(registry)
    val outbox = PeerNoteSocket(io, user = user, registryDirs = listOf(place.registry))
    val helperLog = HelperLog(place.log, WallClock { System.currentTimeMillis() })
}

/** The mirrors this daemon hosts at [place], their inboxes served in [scope] on [io]. */
public class SessionMirrors(
    place: MirrorPlace,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    private val log: LogSink,
) : AutoCloseable {
    private val parts = MirrorParts(place, scope, io, ProcPidIdentity(), System.getProperty("user.name").orEmpty())
    private val lock = Any()

    /** Each machine's mirrors by the id of the session they stand for there. */
    private val byMachine = mutableMapOf<String, MutableMap<String, Mirror>>()

    /** The carrier each machine's mirrors hand a message to, kept so a sender's mirror can be made on demand. */
    private val carriers = mutableMapOf<String, MirrorCarrier>()

    /** How many of this machine's sessions each machine was last shown. */
    private val shownTo = mutableMapOf<String, Int>()

    /** The machines that offered more sessions than there was room for, said once until they fit again. */
    private val overfull = mutableSetOf<String>()

    /** This machine's own running sessions, as another machine is shown them: every one Claude Code registered here
     *  whose process runs, mirrors left out. */
    public fun local(): List<MirroredSession> = ownRecords().mapNotNull { record ->
        record.sessionId?.let { id ->
            val heardAt = listOfNotNull(record.process.updatedAt, record.status.updatedAt).maxOrNull()
            MirroredSession(id, record.name ?: id.take(SHORT_ID_CHARS), heardAt)
        }
    }

    /** How sessions cross with [machine], as the Sharing page shows it. */
    public fun counts(machine: String): MirrorCounts = synchronized(lock) {
        MirrorCounts(byMachine[machine]?.size ?: 0, shownTo[machine])
    }

    /** [machine] was just shown [sessions] of this machine's running sessions. */
    public fun shown(machine: String, sessions: Int) {
        synchronized(lock) { shownTo[machine] = sessions }
    }

    /** Withdraws [machine]'s mirrors and forgets it: its link went quiet, so nothing crosses with it until it looks
     *  again. */
    public fun withdraw(machine: String) {
        synchronized(lock) {
            byMachine.remove(machine)?.values?.forEach(Mirror::close)
            carriers.remove(machine)
            shownTo.remove(machine)
            overfull.remove(machine)
        }
    }

    /** Makes [sessions] the mirrors of [machine]: each new one registered, each gone one withdrawn, a renamed one
     *  registered anew under its new name. Each hands what it takes to [carrier]. */
    public fun mirror(machine: String, sessions: List<MirroredSession>, carrier: MirrorCarrier) {
        synchronized(lock) { reconcile(machine, sessions, carrier) }
    }

    private fun reconcile(machine: String, sessions: List<MirroredSession>, carrier: MirrorCarrier) {
        carriers[machine] = carrier
        val held = byMachine.getOrPut(machine) { mutableMapOf() }
        val wanted = MirrorNames.of(sessions).associateBy { it.id }
        held.entries.removeAll { (id, mirror) ->
            val stale = wanted[id]?.name != mirror.session.name || !mirror.alive()
            if (stale) mirror.close()
            stale
        }
        held.forEach { (id, mirror) -> wanted[id]?.let(mirror::heard) }
        val fresh = wanted.values.filter { it.id !in held }
        val room = (MIRRORS_AT_MOST - byMachine.values.sumOf { it.size }).coerceAtLeast(0)
        for (session in fresh.take(room)) {
            open(machine, session, carrier)?.let { held[session.id] = it }
        }
        roomFor(machine, fresh.size - room)
        held.values.forEach { it.carrier = carrier }
        if (held.isEmpty()) byMachine.remove(machine)
    }

    /** Delivers [text] from [from], a session on [machine], to the session here whose id is [to]. It goes from the
     *  mirror of [from], made now when there is none yet, so a reply to it travels back. */
    public suspend fun deliver(to: String, machine: String, from: MirroredSession, text: String): MirrorCarried {
        val target = ownRecords().singleOrNull { it.sessionId == to }
            ?: return MirrorCarried(DeliveryStatus.REFUSED, "no session here has the id $to")
        val sender = senderOf(machine, from)
            ?: return MirrorCarried(DeliveryStatus.REFUSED, "$machine has not offered its sessions here")
        return when (val sent = parts.outbox.send(target, text, sender.from)) {
            is SendOutcome.Submitted -> MirrorCarried(DeliveryStatus.DELIVERED)
            is SendOutcome.Refused -> MirrorCarried(DeliveryStatus.REFUSED, sent.reason)
            is SendOutcome.Failed -> MirrorCarried(DeliveryStatus.DROPPED, sent.reason)
        }
    }

    /** Withdraws every mirror. */
    override fun close() {
        synchronized(lock) {
            byMachine.values.flatMap { it.values }.forEach(Mirror::close)
            byMachine.clear()
            carriers.clear()
        }
        parts.helperLog.flush()
    }

    private fun senderOf(machine: String, from: MirroredSession): Mirror? = synchronized(lock) {
        val held = byMachine[machine]?.get(from.id)
        if (held != null && held.alive()) return@synchronized held
        held?.close()
        val carrier = carriers[machine] ?: return@synchronized null
        if (byMachine.values.sumOf { it.size } >= MIRRORS_AT_MOST) return@synchronized null
        val others = byMachine[machine]?.values?.map { it.session }.orEmpty()
        val named = MirrorNames.of(others + from).last()
        open(machine, named, carrier)?.also { byMachine.getOrPut(machine) { mutableMapOf() }[from.id] = it }
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

    private fun open(machine: String, session: MirroredSession, carrier: MirrorCarrier): Mirror? {
        val mirror = Mirror(machine, session, carrier, parts)
        val refused = mirror.open() ?: return mirror
        log(
            "share: ${LogSafe.str(session.name)} of ${LogSafe.str(machine)} cannot be mirrored here: " +
                LogSafe.str(refused),
        )
        return null
    }

    private fun ownRecords(): List<SessionRecord> = parts.registry.read().filter {
        it.availability != SessionAvailability.GONE && it.client.kind != MIRROR_KIND
    }
}

// why: an unnamed session is shown by the start of its id, as Claude Code shows one.
internal const val SHORT_ID_CHARS = 8

// why: each mirror is a process and a socket, and any machine on the network may join; 256 is several machines' worth
// of a busy day's sessions, and no machine can make this one start more.
private const val MIRRORS_AT_MOST = 256
