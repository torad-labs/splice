// The writer against a real unix socket: what it sends, and each target it refuses without writing a byte.
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PeerNoteSocketTest {
    private fun perms(text: String) = PosixFilePermissions.fromString(text)

    private fun record(socket: Path, version: String? = "2.1.285") = SessionRecord(
        pid = 1,
        sessionId = "s-1",
        cwd = null,
        name = null,
        kind = null,
        version = version,
        status = "idle",
        statusUpdatedAt = null,
        startedAt = null,
        updatedAt = 1,
        messagingSocketPath = socket.toString(),
        route = SessionRoute.Unknown,
        availability = SessionAvailability.LIVE,
    )

    /** A listening socket in a 0700 directory, 0600 itself, and what it was sent once the sender closed. */
    private data class Inbox(val dir: Path, val socket: Path, val received: CompletableFuture<String>)

    private fun inbox(root: Path, dirMode: String = "rwx------", socketMode: String = "rw-------"): Inbox {
        val dir = Files.createDirectory(root.resolve("cc"), PosixFilePermissions.asFileAttribute(perms(dirMode)))
        val socket = dir.resolve("1.sock")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        Files.setPosixFilePermissions(socket, perms(socketMode))
        val received = CompletableFuture<String>()
        // A plain thread, not the common pool: the test JVM may run that pool with no workers.
        Thread {
            received.complete(
                server.use { s -> s.accept().use { Channels.newInputStream(it).readBytes().toString(Charsets.UTF_8) } },
            )
        }.apply { isDaemon = true }.start()
        return Inbox(dir, socket, received)
    }

    private fun send(target: SessionRecord): NoteOutcome = runBlocking {
        PeerNoteSocket(Dispatchers.IO, newId = { "id-1" }).send(target, "run the gate")
    }

    private fun refusal(target: SessionRecord) = assertInstanceOf(NoteOutcome.Refused::class.java, send(target))

    @Test
    fun `a note is written as its frame to a socket that passes every check, and the answer is submitted`(
        @TempDir root: Path,
    ) {
        val inbox = inbox(root)
        assertEquals(NoteOutcome.Submitted("id-1"), send(record(inbox.socket)))
        val frame = inbox.received.get(5, TimeUnit.SECONDS)
        assertEquals(PeerNoteFrame.encode("run the gate", "id-1"), frame)
    }

    @Test
    fun `a session on any version the probe proved is sent the note`(@TempDir root: Path) {
        // Installed builds that tools/probes/claude-code-peer-note.ts proved accept the frame and refuse its mutant.
        for (version in listOf("2.1.282", "2.1.283", "2.1.284", "2.1.285", "2.1.286", "2.1.287")) {
            val inbox = inbox(root.resolve(version).also { Files.createDirectory(it) })
            assertEquals(NoteOutcome.Submitted("id-1"), send(record(inbox.socket, version)))
            assertEquals(PeerNoteFrame.encode("run the gate", "id-1"), inbox.received.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a session on a version nobody checked is refused, and so is one that reports none`(@TempDir root: Path) {
        val inbox = inbox(root)
        for (version in listOf("2.1.300", null)) {
            assertEquals(HttpStatusCode.UnprocessableEntity, refusal(record(inbox.socket, version)).status)
        }
        assertFalse(inbox.received.isDone)
    }

    @Test
    fun `a socket or directory open to other users is refused without connecting`(@TempDir root: Path) {
        val open = inbox(root, socketMode = "rw-rw-rw-")
        assertEquals("the socket is open to other users", refusal(record(open.socket)).reason)
        Files.setPosixFilePermissions(open.dir, perms("rwxr-xr-x"))
        assertEquals("the socket's directory is open to other users", refusal(record(open.socket)).reason)
        assertFalse(open.received.isDone)
    }

    @Test
    fun `a link to a socket, and a plain file where a socket should be, are refused`(@TempDir root: Path) {
        val inbox = inbox(root)
        val link = Files.createSymbolicLink(inbox.dir.resolve("link.sock"), inbox.socket)
        assertEquals("the socket is a symbolic link", refusal(record(link)).reason)
        val mode = PosixFilePermissions.asFileAttribute(perms("rw-------"))
        val file = Files.createFile(inbox.dir.resolve("file.sock"), mode)
        assertEquals("the socket path is not a socket", refusal(record(file)).reason)
        val dirLink = Files.createSymbolicLink(root.resolve("dir-link"), inbox.dir)
        assertEquals(
            "the socket's directory is a symbolic link",
            refusal(record(dirLink.resolve("1.sock"))).reason,
        )
        assertFalse(inbox.received.isDone)
    }

    @Test
    fun `a socket file nobody listens on fails and says so`(@TempDir root: Path) {
        val mode = PosixFilePermissions.asFileAttribute(perms("rwx------"))
        val socket = Files.createDirectory(root.resolve("cc"), mode).resolve("1.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { it.bind(UnixDomainSocketAddress.of(socket)) }
        Files.setPosixFilePermissions(socket, perms("rw-------"))
        assertInstanceOf(NoteOutcome.Failed::class.java, send(record(socket)))
    }
}
