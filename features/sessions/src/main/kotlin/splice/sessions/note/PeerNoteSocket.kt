// NEW: V4-444 — writes one note frame to a live session's inbox socket, and refuses every target it cannot vouch for. The audit's rules
// (splice-builder2, Sep 29 9:36 PM CT) are the checks below: a checked Claude Code version, a socket that is a real socket owned by this
// user, the socket and its directory not links, the directory one only this user can enter (so nobody else can swap what is behind the
// path), the connected peer owned by this user, and only the text frame written: no control action, no attachment, no sender mode, no token. Transport completion is all the close proves, so the
// answer is "submitted", never "delivered", and a failed write is not retried.
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import jdk.net.ExtendedSocketOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import splice.sessions.registry.SessionRecord
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID

// why: the client's own sender gives a socket five seconds; a console that waited longer would hang a request on a session that is not reading.
private const val NOTE_SEND_TIMEOUT_MS = 5_000L

// why: S_IFMT from stat(2): the bits of a file's mode that say what kind of file it is.
private const val FILE_TYPE_MASK = 0xF000

// why: S_IFSOCK from stat(2): the file-type value that means a socket.
private const val SOCKET_TYPE = 0xC000

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

public class PeerNoteSocket(
    private val io: CoroutineDispatcher,
    private val audited: Set<String> = PeerNoteAbi.AUDITED_VERSIONS,
    private val newId: NoteIds = NoteIds { UUID.randomUUID().toString() },
    private val timeoutMs: Long = NOTE_SEND_TIMEOUT_MS,
    private val user: String = System.getProperty("user.name").orEmpty(),
) : SessionNoteSender {
    override suspend fun send(target: SessionRecord, text: String): NoteOutcome {
        val version = target.version
        if (version == null || version !in audited) {
            val runs = "Claude Code ${version ?: "of unknown version"}"
            val only = audited.sorted().joinToString(", ")
            return NoteOutcome.Refused(
                HttpStatusCode.UnprocessableEntity,
                "the session runs $runs, and notes are only sent to $only",
            )
        }
        val socket = target.messagingSocketPath?.let(Path::of)
            ?: return NoteOutcome.Refused(HttpStatusCode.Conflict, "the session has no messaging socket")
        val id = newId.next()
        val frame = PeerNoteFrame.encode(text, id).toByteArray(Charsets.UTF_8)
        return withTimeoutOrNull(timeoutMs) { runInterruptible(io) { deliver(socket, frame, id) } }
            ?: NoteOutcome.Failed("the session's inbox did not take the note in time")
    }

    private fun deliver(socket: Path, frame: ByteArray, id: String): NoteOutcome {
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
        // A write that failed is reported and never retried: part of the note may already have arrived.
        return sent.fold({ NoteOutcome.Submitted(id) }, { NoteOutcome.Failed(SafeFailureText.render(it)) })
    }

    /** Why this socket may not be written to, or null when it may: a real socket, this user's, in a directory only this user enters,
     *  reached by no link. */
    private fun unsafe(socket: Path): String? {
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

    private fun isSocket(path: Path): Boolean {
        val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as? Int ?: return false
        return mode and FILE_TYPE_MASK == SOCKET_TYPE
    }

    private fun onlyThisUser(path: Path): Boolean {
        if (Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name != user) return false
        return Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).none { it in OTHERS }
    }
}
