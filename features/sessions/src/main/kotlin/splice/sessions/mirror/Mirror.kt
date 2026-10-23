// NEW: LAN share, phase 2, milestone 6 (docs/specs/lan-share.md). One session of another machine, made a Claude Code
// peer here: in every session's ListAgents as `<machine>/<name>`, and taking SendMessage. Claude Code 2.1.309 lists a
// record only when it is named `<pid>.json` for a live pid, one per pid (tools/probes/claude-code-mirror-anchor.ts, Oct
// 23), so each mirror holds a pid of its own: an anchor, `/bin/cat` reading a pipe from the daemon, which exits when
// the daemon does, however it ends. The daemon holds the socket, which the probe showed delivers. The socket sits in
// Claude Code's own sockets folder, where a reply to it is allowed, and is served by the peers' inbox loop
// (PeerInboxServer): this user's processes only, a token from the key file, a line cap. Each message a session here
// writes it goes to the carrier, which takes it to the session it stands for; a refusal goes back to the sender as a
// receipt, in words.
package splice.sessions.mirror

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import splice.core.GATEWAY_VERSION
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.InboxHandler
import splice.sessions.peer.PeerConnections
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SenderCheck
import splice.sessions.peer.helper.PEER_TOKEN_BYTES
import splice.sessions.peer.helper.PeerInboxServer
import splice.sessions.peer.helper.RECORD_MODE
import splice.sessions.peer.helper.RegistryKeyFile
import splice.sessions.peer.helper.RegistryRecordFile
import splice.sessions.peer.helper.SocketBind
import splice.sessions.registry.MIRROR_KIND
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.HexFormat
import java.util.UUID

// why: the anchor holds a pid and nothing else; cat reads its pipe from the daemon and exits at its end, which comes
// when the daemon exits, however it exits.
private const val ANCHOR = "/bin/cat"

// why: a mirror stands for a session on another machine, whose folders mean nothing here.
private const val NO_CWD = "/"

internal class Mirror(
    val machine: String,
    val session: MirroredSession,
    @Volatile var carrier: MirrorCarrier,
    private val parts: MirrorParts,
) {
    private val anchor: Process = ProcessBuilder(ANCHOR)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    private val pid: Long = anchor.pid()
    private val socket: Path = parts.place.sockets.resolve("$pid.sock")
    private val token = HexFormat.of().formatHex(ByteArray(PEER_TOKEN_BYTES).also(SecureRandom()::nextBytes))

    /** The address and name a message from this mirror carries: what the session it reaches replies to. Never
     *  `<name>@<machine>`: Claude Code's SendMessage refuses an @ as a teammate's team ("to must be a bare teammate
     *  name", 2.1.309, Oct 23), while it lists and delivers to `<machine>/<name>`. */
    val from: PeerSender = PeerSender(socket.toString(), "${MirrorNames.safe(machine)}/${session.name}")

    private var channel: ServerSocketChannel? = null
    private var serving: Job? = null
    private val openedAt = System.currentTimeMillis()
    private val procStart by lazy { parts.identity.procStart(pid) }
    private val domain by lazy { parts.identity.hostDomain() }

    /** When its session was last heard from on its machine, which its record's times say; null until that machine
     *  says, when they say the mirror opened. */
    @Volatile
    private var heardAt: Long? = session.heardAt

    /** Whether its anchor still holds the pid its record names. */
    fun alive(): Boolean = anchor.isAlive

    /** Binds its socket, writes its key file and then its record, and serves; why it could not, or null. */
    fun open(): String? {
        val bound = when (val bind = parts.sockets.bind(socket)) {
            is SocketBind.Refused -> return bind.reason.also { close() }
            is SocketBind.Bound -> bind.channel
        }
        channel = bound
        val written = Cancellables.runCatchingCancellable { register() }.exceptionOrNull()
        if (written != null) {
            close()
            return "its registry record cannot be written: ${SafeFailureText.render(written)}"
        }
        val inbox =
            PeerInboxServer(bound, token, Connections(), parts.targets, parts.helperLog, parts.inboxes, parts.user)
        serving = parts.scope.launch { inbox.serve() }
        return null
    }

    /** Takes when [listed], its session as its machine lists it now, was last heard from, and rewrites the record when
     *  that moved, so a listing here reads the session live or stale as its own machine does. */
    fun heard(listed: MirroredSession) {
        val at = listed.heardAt ?: return
        if (at == heardAt) return
        heardAt = at
        Cancellables.runCatchingCancellable { writeRecord() }
            .onFailure { parts.helperLog.error("record unrefreshed", it) }
    }

    /** Removes its record and then its key file, closes its socket and ends its anchor. */
    fun close() {
        val dir = parts.place.registry
        Cancellables.discard(
            Cancellables.runCatchingCleanup {
                Files.deleteIfExists(parts.files.record(dir, pid))
                Files.deleteIfExists(parts.files.key(dir, pid, socket))
            },
            "a record left behind names a pid that is gone, so no session lists it",
        )
        serving?.cancel()
        Cancellables.discard(Cancellables.runCatchingCleanup { channel?.close() }, "the mirror is going")
        Cancellables.discard(Cancellables.runCatchingCleanup { Files.deleteIfExists(socket) }, "the mirror is going")
        anchor.destroy()
    }

    private fun register() {
        val key = parts.files.encode(RegistryKeyFile(token, procStart, domain))
        SecureFile.writeAtomic0600(parts.files.key(parts.place.registry, pid, socket), key)
        writeRecord()
    }

    private fun writeRecord() {
        val heard = heardAt ?: openedAt
        val record = RegistryRecordFile(
            pid = pid,
            sessionId = UUID.nameUUIDFromBytes("$machine/${session.id}".toByteArray()).toString(),
            cwd = NO_CWD,
            startedAt = openedAt,
            procStart = procStart,
            version = "$MIRROR_KIND/$GATEWAY_VERSION",
            peerFeatures = emptyList(),
            kind = MIRROR_KIND,
            entrypoint = MIRROR_KIND,
            pidDomain = domain,
            messagingSocketPath = socket.toString(),
            name = from.name,
            nameSince = openedAt,
            updatedAt = heard,
            statusUpdatedAt = heard,
        )
        val path = parts.files.record(parts.place.registry, pid)
        SecureFile.writeAtomic0600(path, parts.files.encode(record))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(RECORD_MODE))
    }

    /** What [carried] says to the sender: nothing when it was delivered or is on its way, else a receipt in words. */
    private suspend fun answer(frame: InboundFrame.Message, carried: MirrorCarried): DeliveryStatus? {
        val status = carried.status
        val to = frame.claimedAddress
        val id = frame.msgId
        val receipted = status == null || status == DeliveryStatus.DELIVERED || to == null || id == null ||
            parts.outbox.receipt(to, id, status, carried.detail, from)
        if (!receipted) parts.helperLog.count("receipt unsent")
        return status
    }

    private suspend fun denied(frame: InboundFrame.Message): DeliveryStatus? =
        answer(frame, MirrorCarried(DeliveryStatus.DENIED, "the sender did not present this mirror's token"))

    /** Each connection to the mirror: one that presented its token is carried on, any other is denied. */
    private inner class Connections : PeerConnections {
        override fun forConnection(tokenPresented: Boolean): InboxHandler = Carried(tokenPresented)

        override suspend fun onDenied(frame: InboundFrame.Message) {
            denied(frame)
        }
    }

    private inner class Carried(private val vouched: Boolean) : InboxHandler {
        override suspend fun onMessage(frame: InboundFrame.Message, sender: SenderCheck): DeliveryStatus? {
            if (!vouched) return denied(frame)
            val id = sender.sessionId?.takeIf { sender.registered }
                ?: return answer(frame, MirrorCarried(DeliveryStatus.REFUSED, "only a session running here can write"))
            val writer = MirroredSession(id, sender.name ?: id.take(SHORT_ID_CHARS))
            val carried = Cancellables.runCatchingCancellable { carrier.carry(session, writer, frame.text) }
                .getOrElse { MirrorCarried(DeliveryStatus.DROPPED, SafeFailureText.render(it)) }
            return answer(frame, carried)
        }

        override suspend fun onReceipt(frame: InboundFrame.Receipt, sender: SenderCheck) {
            // A receipt to a mirror answers a message it delivered for another machine; PeerInboxServer counted it.
        }

        override suspend fun onDenied(frame: InboundFrame.Message) {
            denied(frame)
        }
    }
}

/** The name part of a mirror's `<machine>/<name>`: what Claude Code can list and an envelope can carry, and no two
 *  of one machine's alike, so a SendMessage by name reaches one. */
internal object MirrorNames {
    private val unsafe = Regex("[^A-Za-z0-9._-]+")

    // why: a session name is a label; past 64 characters a listing cuts it anyway.
    private const val MAX_CHARS = 64

    // why: enough of an id to tell two sessions of one name apart; Claude Code's own ids differ in their first 8.
    private const val ID_CHARS = 8

    // why: what a session is called when nothing of its name is left to show.
    private const val UNNAMED_SESSION = "session"

    /** [sessions] each under its safe name, one that would repeat another's followed by the start of its id. */
    fun of(sessions: List<MirroredSession>): List<MirroredSession> {
        val safe = sessions.map { safe(it.name) }
        val repeated = safe.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return sessions.zip(safe) { session, name ->
            val tag = safe(session.id).take(ID_CHARS)
            session.copy(name = if (name in repeated) "${name.take(MAX_CHARS - ID_CHARS - 1)}-$tag" else name)
        }
    }

    /** [name] with each run of what a listing or an envelope cannot carry made one dash, at most [MAX_CHARS] long. */
    fun safe(name: String): String = name.replace(unsafe, "-").take(MAX_CHARS).ifEmpty { UNNAMED_SESSION }
}
