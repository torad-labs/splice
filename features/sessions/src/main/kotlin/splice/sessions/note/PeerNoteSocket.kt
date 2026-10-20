// NEW: V4-444 — writes one frame to a live session's inbox socket, and refuses every target it cannot vouch for. The audit's rules
// (splice-builder2, Sep 29 9:36 PM CT) are the checks below: a version the wire rule accepts, a socket that is a real socket owned by this
// user, the socket and its directory not links, the directory one only this user can enter (so nobody else can swap what is behind the
// path), and the connected peer owned by this user. Transport completion is all the close proves, so the answer is "submitted", never
// "delivered", and a failed write is not retried.
//
// Peer messaging (docs/specs/peer-messaging.md 1.1 and 1.2) makes it the PeerOutbox: a peer's message and a receipt go out the same
// way, opened by the target's auth line when its key file is found beside its registration. A console note keeps its own frame.
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.Cancellables
import splice.core.util.JsonScalars
import splice.core.util.PathProbe
import splice.core.util.SafeFailureText
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerOutbox
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.registry.SessionRecord
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

// why: the client's own sender gives a socket five seconds; a console that waited longer would hang a request on a session that is not reading.
private const val NOTE_SEND_TIMEOUT_MS = 5_000L

// why: S_IFMT from stat(2): the bits of a file's mode that say what kind of file it is.
private const val FILE_TYPE_MASK = 0xF000

// why: S_IFSOCK from stat(2): the file-type value that means a socket.
private const val SOCKET_TYPE = 0xC000

// why: a key file holds three short fields; anything larger is not one, and is not read whole.
private const val MAX_KEY_FILE_BYTES = 4_096L

// why: a peerToken is 32 hex characters; a longer value is not a token the client minted.
private const val MAX_TOKEN_CHARS = 256

// why: the scheme Claude Code writes before a socket path in every `from` address.
private const val ADDRESS_SCHEME = "uds:"

private val OTHERS = setOf(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/** Where a note's message id comes from: random in the daemon, fixed in a test. */
public fun interface NoteIds {
    public fun next(): String
}

/**
 * [registryDirs] are where a receipt's target key file is looked for, by its socket's hash: a receipt carries an address,
 * not a registration. A message's key file is looked for in the directory its target's registration was read from.
 */
public class PeerNoteSocket(
    private val io: CoroutineDispatcher,
    private val newId: NoteIds = NoteIds { UUID.randomUUID().toString() },
    private val timeoutMs: Long = NOTE_SEND_TIMEOUT_MS,
    private val user: String = System.getProperty("user.name").orEmpty(),
    private val registryDirs: List<Path> = emptyList(),
) : SessionNoteSender, PeerOutbox {
    private val keys = PeerKeyFiles(registryDirs)

    override suspend fun send(target: SessionRecord, text: String): NoteOutcome {
        PeerVersions.refusal(target.client)?.let { return NoteOutcome.Refused(HttpStatusCode.UnprocessableEntity, it) }
        val socket = target.process.messagingSocketPath?.let(PathProbe::spelled)
            ?: return NoteOutcome.Refused(HttpStatusCode.Conflict, NO_SOCKET)
        val frame = keys.auth(target) + PeerNoteFrame.encode(text, newId.next())
        return write(socket, frame) ?: NoteOutcome.Failed("the session's inbox did not take the note in time")
    }

    override suspend fun send(target: SessionRecord, text: String, sender: PeerSender): SendOutcome {
        val socket = target.process.messagingSocketPath?.let(PathProbe::spelled)
        val refused = refusal(target, socket, text, sender)
        if (refused != null || socket == null) {
            return refused ?: SendOutcome.Refused(HttpStatusCode.Conflict.value, NO_SOCKET)
        }
        val id = newId.next()
        val frame = keys.auth(target) + PeerWire.encodeMessage(id, target.sessionId, text, sender)
        return when (val outcome = write(socket, frame)) {
            NoteOutcome.Submitted -> SendOutcome.Submitted(id)
            is NoteOutcome.Refused -> SendOutcome.Refused(outcome.status.value, outcome.reason)
            is NoteOutcome.Failed -> SendOutcome.Failed(outcome.reason)
            null -> SendOutcome.Failed("the inbox did not take the message in time")
        }
    }

    /** A receipt goes once and is never retried. A `denied` one goes with no auth line, as the spec has it (1.3): the
     *  connection it answers presented a token this side would not take, so this side presents none either. */
    override suspend fun receipt(
        toAddress: String,
        origMsgId: String,
        status: DeliveryStatus,
        detail: String?,
        sender: PeerSender,
    ): Boolean {
        val raw = toAddress.takeIf { it.startsWith(ADDRESS_SCHEME) }?.removePrefix(ADDRESS_SCHEME)
        val socket = raw?.let(PathProbe::spelled)?.takeIf { it.isAbsolute }
        if (socket == null || unwritable(sender) != null) return false
        val token = raw?.takeUnless { status == DeliveryStatus.DENIED }?.let(keys::tokenByAddress)
        val frame = token?.let(PeerWire::encodeAuth).orEmpty() +
            PeerWire.encodeReceipt(newId.next(), origMsgId, status, detail, sender)
        return write(socket, frame) == NoteOutcome.Submitted
    }

    /** Why a peer's message may not go to [target] on [socket], or null when it may. */
    private fun refusal(target: SessionRecord, socket: Path?, text: String, sender: PeerSender): SendOutcome? {
        val version = PeerVersions.refusal(target.client)
        val wording = PeerWire.textRefusal(text)
        val unwritable = unwritable(sender)
        return when {
            version != null -> SendOutcome.Refused(HttpStatusCode.UnprocessableEntity.value, version)
            socket == null -> SendOutcome.Refused(HttpStatusCode.Conflict.value, NO_SOCKET)
            wording != null -> {
                val status = if (text.isBlank()) HttpStatusCode.BadRequest else HttpStatusCode.PayloadTooLarge
                SendOutcome.Refused(status.value, wording)
            }
            unwritable != null -> SendOutcome.Failed(unwritable)
            else -> null
        }
    }

    /** Why this socket may not be written to, or null when it may: a real socket, this user's, in a directory only this user enters,
     *  reached by no link. The helper vets its own inbox folder with it too. */
    public fun unsafe(socket: Path): String? {
        val dir = socket.parent ?: return "the socket path names no directory"
        val checked = Cancellables.runCatchingCancellable {
            when {
                Files.isSymbolicLink(dir) -> "the socket's directory is a symbolic link"
                Files.isSymbolicLink(socket) -> "the socket is a symbolic link"
                !onlyThisUser(dir) -> "the socket's directory is open to other users"
                !onlyThisUser(socket) -> "the socket is open to other users"
                !isSocket(socket) -> "the socket path is not a socket"
                else -> null
            }
        }
        return checked.getOrElse { "the socket cannot be vetted: ${SafeFailureText.render(it)}" }
    }

    /** The frame written within the timeout, or null when the timeout ran out first. */
    private suspend fun write(socket: Path, frame: String): NoteOutcome? {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        return withTimeoutOrNull(timeoutMs) { runInterruptible(io) { deliver(socket, bytes) } }
    }

    private fun deliver(socket: Path, frame: ByteArray): NoteOutcome {
        unsafe(socket)?.let { return NoteOutcome.Refused(HttpStatusCode.Conflict, it) }
        val sent = Cancellables.runCatchingCancellable {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket))
                val owner = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user().name
                if (owner != user) {
                    return NoteOutcome.Refused(HttpStatusCode.Conflict, "the socket is answered by another user")
                }
                val buffer = ByteBuffer.wrap(frame)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.shutdownOutput()
            }
        }
        // A write that failed is reported and never retried: part of the frame may already have arrived.
        return sent.fold({ NoteOutcome.Submitted }, { NoteOutcome.Failed(SafeFailureText.render(it)) })
    }

    /** Why [sender] cannot be written into an envelope's attributes, or null when it can. */
    private fun unwritable(sender: PeerSender): String? =
        if (PeerWire.attributeSafe(sender.name) && PeerWire.attributeSafe(sender.socket)) {
            null
        } else {
            "the sender's name or socket cannot be written in an envelope"
        }

    private fun isSocket(path: Path): Boolean {
        val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int ?: return false
        return mode and FILE_TYPE_MASK == SOCKET_TYPE
    }

    private fun onlyThisUser(path: Path): Boolean {
        if (Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name != user) return false
        return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).none { it in OTHERS }
    }
}

/** Claude Code key files: the `peerToken` a frame to a socket opens with, found beside the registration that names the
 *  socket, or by the socket's hash in [registryDirs] when only an address is known. */
internal class PeerKeyFiles(private val registryDirs: List<Path>) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The auth line for [target], or nothing when its key file is not beside its registration: both shapes are taken on
     *  Linux. The key file is named for the socket path as the registration spells it, which is what Claude Code hashed. */
    fun auth(target: SessionRecord): String {
        val process = target.process
        val dir = process.registryDir?.let(PathProbe::spelled)
        val name = process.pid?.let { pid -> process.messagingSocketPath?.let { "$pid.${socketHash(it)}.key" } }
        return name?.let { dir?.resolve(it) }?.let(::token)?.let(PeerWire::encodeAuth).orEmpty()
    }

    /** The token of the first key file in [registryDirs] named for [socketPath], whatever pid it names. */
    fun tokenByAddress(socketPath: String): String? {
        val suffix = ".${socketHash(socketPath)}.key"
        return registryDirs.asSequence()
            .flatMap { dir -> PathProbe.entries(dir).asSequence() }
            .filter { it.fileName.toString().endsWith(suffix) }
            .firstNotNullOfOrNull(::token)
    }

    /** The `peerToken` a key file holds, or null when the file is missing, a link, oversized, or holds no token. A missing
     *  token only means the frame goes with no auth line, which Claude Code takes on Linux. */
    private fun token(file: Path): String? {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || oversized(file)) return null
        val obj = PathProbe.text(file)?.let { JsonScalars.objectOrNull(json, it) } ?: return null
        return (obj["peerToken"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN_CHARS }
    }

    /** True when [file] is past the key-file cap, or its size cannot be read: either way it is no key file. */
    private fun oversized(file: Path): Boolean = try {
        Files.size(file) > MAX_KEY_FILE_BYTES
    } catch (_: IOException) {
        true
    }

    /** The key file's name for a socket: the lowercase hex sha256 of its path, as Claude Code names it. */
    private fun socketHash(socketPath: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(socketPath.toByteArray(Charsets.UTF_8)))
}
