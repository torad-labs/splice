// NEW: peer messaging, package B (docs/specs/peer-messaging.md 2 and 4.1 step 2) — where a peer helper's inbox socket
// lives and how it is bound. The socket goes in Claude Code's own sockets folder, $XDG_RUNTIME_DIR/cc-socks, because a
// client sends receipts only to a `from` in that folder; a path past 103 bytes moves to /tmp/cc-socks-<uid>, as the
// client's own does, and gets no receipts. The folder is vetted the way PeerNoteSocket vets a target's (this user's,
// closed to group and others, no link), created owner-only when absent, and the socket is closed to others once bound.
package splice.sessions.peer.helper

import splice.core.util.Cancellables
import splice.core.util.FileTightening
import splice.core.util.SafeFailureText
import splice.core.util.SecureFile
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

// why: sun_path holds 108 bytes on Linux, and Claude Code moves a socket path longer than 103 bytes out to /tmp.
private const val MAX_SOCKET_PATH_BYTES = 103

/** Claude Code's sockets folder under the runtime directory. */
private const val CLIENT_FOLDER = "cc-socks"

private val GROUP_OR_OTHERS = setOf(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.GROUP_EXECUTE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE,
    PosixFilePermission.OTHERS_EXECUTE,
)

/** A bound inbox socket, or why none could be bound. */
public sealed class SocketBind {
    public data class Bound(val channel: ServerSocketChannel, val socket: Path) : SocketBind()

    public data class Refused(val reason: String) : SocketBind()
}

/** The sockets of this user's peer helpers: [runtimeDir] is $XDG_RUNTIME_DIR, [uid] names the /tmp fallback. */
public class PeerSockets(runtimeDir: Path?, private val uid: Long?, private val user: String) {
    private val clientFolder: Path? = runtimeDir?.resolve(CLIENT_FOLDER)

    /** The socket a helper with [pid] listens on. */
    public fun pathFor(pid: Long): Path {
        val name = "$pid.sock"
        val preferred = clientFolder?.resolve(name)
        if (preferred != null && preferred.toString().toByteArray(Charsets.UTF_8).size <= MAX_SOCKET_PATH_BYTES) {
            return preferred
        }
        return Path.of("/tmp", "cc-socks-${uid ?: user}").resolve(name)
    }

    /** Whether Claude Code will send receipts to [socket]: only to one in its own sockets folder. */
    public fun inClientFolder(socket: Path): Boolean = clientFolder != null && socket.parent == clientFolder

    /** Vets or creates the folder, unlinks a stale socket at [socket], binds, and closes the socket to others. */
    public fun bind(socket: Path): SocketBind {
        val dir = socket.parent ?: return SocketBind.Refused("the socket path names no directory")
        val refusal = folder(dir) ?: unlinkStale(socket)
        return if (refusal != null) SocketBind.Refused(refusal) else listen(socket)
    }

    /** Removes what an earlier helper with this pid left at [socket]; why it could not, or null. */
    private fun unlinkStale(socket: Path): String? =
        Cancellables.runCatchingCancellable { Files.deleteIfExists(socket) }.exceptionOrNull()
            ?.let { "a stale socket stays: ${SafeFailureText.render(it)}" }

    private fun listen(socket: Path): SocketBind {
        val opened = Cancellables.runCatchingCancellable { ServerSocketChannel.open(StandardProtocolFamily.UNIX) }
        val server = opened.getOrElse { return SocketBind.Refused("no socket opens: ${SafeFailureText.render(it)}") }
        val bound = Cancellables.runCatchingCancellable { server.bind(UnixDomainSocketAddress.of(socket)) }
        val refusal = bound.exceptionOrNull()?.let { "the socket cannot be bound: ${SafeFailureText.render(it)}" }
            ?: (SecureFile.ownerOnlyFile(socket) as? FileTightening.Open)?.let { "the socket stays open: ${it.why}" }
        if (refusal == null) return SocketBind.Bound(server, socket)
        Cancellables.discard(Cancellables.runCatchingCancellable { server.close() }, "the refusal is the answer")
        return SocketBind.Refused(refusal)
    }

    /** Why the sockets folder may not hold an inbox, or null when it may; an absent folder is created owner-only. */
    private fun folder(dir: Path): String? {
        val checked = Cancellables.runCatchingCancellable {
            when {
                Files.isSymbolicLink(dir) -> "the sockets folder is a symbolic link"
                !Files.exists(dir, LinkOption.NOFOLLOW_LINKS) ->
                    SecureFile.ownerOnlyDirectory(dir)?.let { "the sockets folder stays open: $it" }
                !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) -> "the sockets folder is not a directory"
                Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).name != user -> "the sockets folder is another user's"
                Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).any { it in GROUP_OR_OTHERS } ->
                    "the sockets folder is open to other users"
                else -> null
            }
        }
        return checked.getOrElse { "the sockets folder cannot be vetted: ${SafeFailureText.render(it)}" }
    }
}
