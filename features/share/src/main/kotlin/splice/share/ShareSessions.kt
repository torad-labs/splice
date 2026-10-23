// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). Sessions across machines, the sharing side. A
// joined machine reads this one's running sessions (`GET /share/v1/sessions`), offers its own (`PUT`), which this
// machine mirrors as `<name>@<that machine>` (SessionMirrors), and writes to one of this machine's sessions
// (`POST .../{id}/message`), from the mirror of its sender here, so the reply comes back through it. A message a
// session here writes to one of those mirrors waits in that machine's outbox until it collects it
// (`GET .../outbox?wait=<s>`), since a joined machine need take no connection in. Every route needs a joined machine's
// token, so the machine is named by the link and never by the request. A machine's mirrors here last three of its
// looks past its last offer, and go when this listener closes: a machine that stopped looking has no session listed.
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import splice.sessions.mirror.MirrorCarried
import splice.sessions.mirror.MirrorCarrier
import splice.sessions.mirror.MirroredSession
import splice.sessions.mirror.SessionMirrors
import splice.sessions.peer.DeliveryStatus
import splice.upstream.Ticker
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** One session as the link spells it: its id on its own machine, its name there, and when it was last heard from there,
 *  in that machine's epoch milliseconds. */
@Serializable
internal data class ShareSession(
    val id: String,
    val name: String,
    @SerialName("heard_at") val heardAt: Long? = null,
)

/** `GET` and `PUT /share/v1/sessions`: a machine's running sessions, and on a `PUT`, how often it looks, which sets how
 *  long its mirrors last here without another. */
@Serializable
internal data class ShareSessions(
    val sessions: List<ShareSession>,
    @SerialName("look_ms") val lookMs: Long? = null,
)

/** `POST /share/v1/sessions/{id}/message`: what a session on the writing machine wrote, and who it is there. */
@Serializable
internal data class ShareNote(val from: ShareSession, val text: String)

/** What became of a note: its delivery status's wire word, null while it is on its way, and why, in words. */
@Serializable
internal data class ShareNoteAnswer(val status: String? = null, val detail: String? = null)

/** A note waiting for the machine that collects it: to which of its sessions, from which session here. */
@Serializable
internal data class ShareOutboxNote(val to: ShareSession, val from: ShareSession, val text: String)

/** `GET /share/v1/sessions/outbox`: the notes collected, oldest first; none when the wait ran out. */
@Serializable
internal data class ShareOutbox(val notes: List<ShareOutboxNote>)

/** The session routes' answers, over [mirrors]; null when this daemon hosts none, and every route says so. */
internal class ShareSessionHost(
    private val mirrors: SessionMirrors?,
    private val relay: ShareRelay,
    private val errors: ShareErrors,
    /** Mirrors are made and withdrawn here: each spawns or ends a process and writes files. */
    private val io: CoroutineDispatcher,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val outboxes = ShareOutboxes()

    /** When each machine's mirrors here end, in epoch ms, unless it offers its sessions again before. */
    private val leases = ConcurrentHashMap<String, Long>()

    /** This machine's running sessions, as [client] is shown them. */
    suspend fun list(call: ApplicationCall, client: String) {
        val hosting = hosted(call) ?: return
        val sessions = hosting.local().map { ShareSession(it.id, it.name, it.heardAt) }
        respond(call, ShareSessions.serializer(), ShareSessions(sessions))
        hosting.shown(client, sessions.size)
    }

    /** [client]'s running sessions, mirrored here from now on. */
    suspend fun offer(call: ApplicationCall, client: String) {
        val hosting = hosted(call) ?: return
        val offered = read(call, ShareSessions.serializer(), SESSIONS_BODY_MAX_BYTES) ?: return
        val lease = ((offered.lookMs ?: LEASE_MAX_MS) * LEASE_LOOKS).coerceIn(LEASE_MIN_MS, LEASE_MAX_MS)
        leases[client] = System.currentTimeMillis() + lease
        withContext(io) { hosting.mirror(client, offered.sessions.map(::mirrored), outboxes.carrier(client)) }
        respond(call, ShareSessions.serializer(), ShareSessions(emptyList()))
    }

    /** A note from a session on [client] to the session here the path names. */
    suspend fun note(call: ApplicationCall, client: String) {
        val hosting = hosted(call) ?: return
        val note = read(call, ShareNote.serializer(), NOTE_BODY_MAX_BYTES) ?: return
        val to = call.parameters["id"].orEmpty()
        val carried = hosting.deliver(to, client, mirrored(note.from), note.text)
        respond(call, ShareNoteAnswer.serializer(), ShareNoteAnswer(carried.status?.wire, carried.detail))
    }

    /** The notes waiting for [client], or the first to arrive within the wait the query asks for (at most
     *  [OUTBOX_MAX_WAIT_S] seconds). */
    suspend fun outbox(call: ApplicationCall, client: String) {
        hosted(call) ?: return
        val wait = call.request.queryParameters["wait"]?.toLongOrNull()?.coerceIn(0L, OUTBOX_MAX_WAIT_S) ?: 0L
        respond(call, ShareOutbox.serializer(), ShareOutbox(outboxes.collect(client, wait)))
    }

    /** Withdraws the mirrors of each machine whose lease ran out, every [SWEEP_MS], until the listener stops. */
    suspend fun sweep(ticker: Ticker) {
        while (ticker.awaitTick(SWEEP_MS)) {
            val now = System.currentTimeMillis()
            val ended = leases.filterValues { it < now }.filter { (client, at) -> leases.remove(client, at) }.keys
            if (ended.isNotEmpty()) withContext(io) { ended.forEach(::withdraw) }
        }
    }

    /** Withdraws every machine's mirrors: the listener is closing. Blocking. */
    fun withdraw() {
        leases.keys.toList().filter { leases.remove(it) != null }.forEach(::withdraw)
    }

    private fun withdraw(client: String) {
        mirrors?.withdraw(client)
    }

    private suspend fun hosted(call: ApplicationCall): SessionMirrors? {
        if (mirrors == null) refuse(call, HttpStatus.SERVICE_UNAVAILABLE, "this machine does not share its sessions")
        return mirrors
    }

    private suspend fun <T> read(call: ApplicationCall, shape: KSerializer<T>, cap: Int): T? {
        val body = relay.body(call, cap, SESSIONS_READ_MS) ?: return null
        val read = Cancellables.runCatchingCancellable { json.decodeFromString(shape, body.decodeToString()) }
        return read.getOrElse {
            refuse(call, HttpStatus.BAD_REQUEST, "the body is not what this route reads")
            null
        }
    }

    private suspend fun <T> respond(call: ApplicationCall, shape: KSerializer<T>, value: T) {
        call.respondText(json.encodeToString(shape, value), ContentType.Application.Json)
    }

    private suspend fun refuse(call: ApplicationCall, status: Int, message: String) {
        call.respondText(errors.body(message), ContentType.Application.Json, HttpStatusCode.fromValue(status))
    }

    private fun mirrored(session: ShareSession) = MirroredSession(session.id, session.name, session.heardAt)
}

/** The notes a session here wrote to a joined machine's mirror, waiting for that machine to collect them. */
internal class ShareOutboxes {
    private val boxes = ConcurrentHashMap<String, Channel<ShareOutboxNote>>()

    /** What [client]'s mirrors hand a note to: its outbox, which a full one refuses as dropped. */
    fun carrier(client: String) = MirrorCarrier { to, from, text ->
        val queued = box(client).trySend(ShareOutboxNote(spelled(to), spelled(from), text)).isSuccess
        val full = MirrorCarried(DeliveryStatus.DROPPED, "$client is not collecting its notes")
        if (queued) MirrorCarried(null) else full
    }

    /** The notes waiting for [client], or the first to arrive within [waitS] seconds. */
    suspend fun collect(client: String, waitS: Long): List<ShareOutboxNote> {
        val box = box(client)
        val first = box.tryReceive().getOrNull() ?: withTimeoutOrNull(waitS.seconds) { box.receive() }
        return generateSequence(first) { box.tryReceive().getOrNull() }.take(OUTBOX_NOTES_AT_ONCE).toList()
    }

    private fun box(client: String): Channel<ShareOutboxNote> =
        boxes.computeIfAbsent(client) { Channel(OUTBOX_CAPACITY) }

    private fun spelled(session: MirroredSession) = ShareSession(session.id, session.name, session.heardAt)
}

internal const val SHARE_SESSIONS_PATH: String = "/share/v1/sessions"

/** The longest a collection waits for a note: under the client's own request deadline for it, so an empty wait ends
 *  as an empty answer and never as a timeout. */
internal const val OUTBOX_MAX_WAIT_S: Long = 25L

// why: a machine's running sessions are a few dozen ids and names; 256 KiB holds hundreds with any JSON spacing.
private const val SESSIONS_BODY_MAX_BYTES = 262_144

// why: a note's text is capped at 32 KiB where it is delivered (PeerWire); with JSON escaping and its sender it fits
// in 128 KiB, and nothing larger is read before it is refused.
private const val NOTE_BODY_MAX_BYTES = 131_072

// why: these bodies are small and come over a LAN or a tailnet in one go; five seconds is the join's own deadline.
private const val SESSIONS_READ_MS = 5_000L

// why: a machine's mirrors outlast three of its looks, so one late or failed look does not withdraw and remake them.
private const val LEASE_LOOKS = 3L

// why: a lease shorter than a second would withdraw a machine's mirrors between two requests of one look.
private const val LEASE_MIN_MS = 1_000L

// why: a machine that stopped looking leaves the other's listing within a minute, whatever look it asked for.
private const val LEASE_MAX_MS = 60_000L

// why: a lease that ran out is noticed within a second.
private const val SWEEP_MS = 1_000L

// why: notes waiting for a machine that is collecting them are a handful; 256 is a machine that stopped collecting,
// and the next one is refused as dropped instead of held.
private const val OUTBOX_CAPACITY = 256

// why: one collection answers with at most this many, so its body stays small; the rest wait for the next.
private const val OUTBOX_NOTES_AT_ONCE = 64
