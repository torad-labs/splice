// The writer against a real unix socket: what it sends, and each target it refuses without writing a byte.
package splice.sessions.note

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PeerNoteSocketTest {
    private val gary = PeerSender("/run/user/1000/cc-socks/42.sock", "gary")

    private fun perms(text: String) = PosixFilePermissions.fromString(text)

    private fun record(socket: Path, version: String? = "2.1.285", protocol: Int? = 1) = SessionRecord(
        sessionId = "s-1",
        name = null,
        status = SessionStatus("idle"),
        route = SessionRoute.Unknown,
        availability = SessionAvailability.LIVE,
        process = SessionProcess(
            pid = 1,
            cwd = null,
            startedAt = null,
            updatedAt = 1,
            messagingSocketPath = socket.toString(),
        ),
        client = SessionClient(kind = null, version = version, peerProtocol = protocol),
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
        assertEquals(NoteOutcome.Submitted, send(record(inbox.socket)))
        val frame = inbox.received.get(5, TimeUnit.SECONDS)
        assertEquals(PeerNoteFrame.encode("run the gate", "id-1"), frame)
    }

    @Test
    fun `a session on any version the probe proved is sent the note`(@TempDir root: Path) {
        // Installed builds that tools/probes/claude-code-peer-note.ts proved accept the frame and refuse its mutant.
        val audited = listOf(
            "2.1.282", "2.1.283", "2.1.284", "2.1.285", "2.1.286", "2.1.287", "2.1.289", "2.1.296",
            "2.1.299", "2.1.300", "2.1.301", "2.1.302", "2.1.303", "2.1.304",
        )
        for (version in audited) {
            val inbox = inbox(root.resolve(version).also { Files.createDirectory(it) })
            assertEquals(NoteOutcome.Submitted, send(record(inbox.socket, version)))
            assertEquals(PeerNoteFrame.encode("run the gate", "id-1"), inbox.received.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a later version, and a peer that is not Claude Code, are sent the note when they say peerProtocol 1`(
        @TempDir root: Path,
    ) {
        for (version in listOf("2.1.305", "2.2.0", "grokbot-mailbox/0.1.0")) {
            val inbox = inbox(root.resolve(version.replace("/", "-")).also { Files.createDirectory(it) })
            assertEquals(NoteOutcome.Submitted, send(record(inbox.socket, version)))
            assertEquals(PeerNoteFrame.encode("run the gate", "id-1"), inbox.received.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a version below 2-1-282, no version, and no peerProtocol 1 are refused with 422 and nothing written`(
        @TempDir root: Path,
    ) {
        val inbox = inbox(root)
        val refused = listOf(
            record(inbox.socket, "2.1.281"),
            record(inbox.socket, null),
            record(inbox.socket, "2.1.305", protocol = null),
            record(inbox.socket, "grokbot-mailbox/0.1.0", protocol = 2),
        )
        for (target in refused) assertEquals(HttpStatusCode.UnprocessableEntity, refusal(target).status)
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

    /** Claude Code's key file for [socket], written into [registry] under [pid] the way the client names it. */
    private fun keyFile(registry: Path, pid: Long, socket: Path, token: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(socket.toString().toByteArray())
        val name = "$pid.${HexFormat.of().formatHex(digest)}.key"
        Files.writeString(registry.resolve(name), "{\"peerToken\":\"$token\",\"procStart\":\"1\"}")
    }

    @Test
    fun `a peer's message opens with the target's token when its key file sits beside its registration`(
        @TempDir root: Path,
    ) {
        val inbox = inbox(root)
        val registry = Files.createDirectory(root.resolve("sessions"))
        keyFile(registry, 1, inbox.socket, "0123456789abcdef0123456789abcdef")
        val target = record(inbox.socket).let { it.copy(process = it.process.copy(registryDir = registry.toString())) }
        val outcome = runBlocking { PeerNoteSocket(Dispatchers.IO, newId = { "id-1" }).send(target, "PONG?", gary) }
        assertEquals(SendOutcome.Submitted("id-1"), outcome)
        assertEquals(
            PeerWire.encodeAuth("0123456789abcdef0123456789abcdef") +
                PeerWire.encodeMessage("id-1", "s-1", "PONG?", gary),
            inbox.received.get(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a peer's message with no key file goes alone, and one over 32,768 bytes is refused with 413 unwritten`(
        @TempDir root: Path,
    ) {
        val inbox = inbox(root)
        val socket = PeerNoteSocket(Dispatchers.IO, newId = { "id-1" })
        val big = runBlocking { socket.send(record(inbox.socket), "x".repeat(PEER_MAX_TEXT_BYTES + 1), gary) }
        assertEquals(413, assertInstanceOf(SendOutcome.Refused::class.java, big).httpStatus)
        assertFalse(inbox.received.isDone)
        assertEquals(SendOutcome.Submitted("id-1"), runBlocking { socket.send(record(inbox.socket), "hi", gary) })
        assertEquals(PeerWire.encodeMessage("id-1", "s-1", "hi", gary), inbox.received.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a receipt finds the token by the address's socket hash, and a denied one goes with no auth line`(
        @TempDir root: Path,
    ) {
        val registry = Files.createDirectory(root.resolve("sessions"))
        val delivered = inbox(root.resolve("a").also { Files.createDirectory(it) })
        keyFile(registry, 999, delivered.socket, "feedfacefeedfacefeedfacefeedface")
        val socket = PeerNoteSocket(Dispatchers.IO, newId = { "r-1" }, registryDirs = listOf(registry))
        val self = PeerSender(delivered.dir.resolve("42.sock").toString(), "gary")
        val address = "uds:${delivered.socket}"
        assertTrue(runBlocking { socket.receipt(address, "m-1", DeliveryStatus.DELIVERED, null, self) })
        assertEquals(
            PeerWire.encodeAuth("feedfacefeedfacefeedfacefeedface") +
                PeerWire.encodeReceipt("r-1", "m-1", DeliveryStatus.DELIVERED, null, self),
            delivered.received.get(5, TimeUnit.SECONDS),
        )
        val denied = inbox(root.resolve("b").also { Files.createDirectory(it) })
        keyFile(registry, 998, denied.socket, "feedfacefeedfacefeedfacefeedface")
        val deniedSelf = PeerSender(denied.dir.resolve("42.sock").toString(), "gary")
        val deniedAddress = "uds:${denied.socket}"
        assertTrue(
            runBlocking { socket.receipt(deniedAddress, "m-2", DeliveryStatus.DENIED, "bad peer token", deniedSelf) },
        )
        assertEquals(
            PeerWire.encodeReceipt("r-1", "m-2", DeliveryStatus.DENIED, "bad peer token", deniedSelf),
            denied.received.get(5, TimeUnit.SECONDS),
        )
    }

    @Test
    fun `a receipt goes nowhere outside the sender's own sockets folder, by another folder, a dot-dot or a link`(
        @TempDir root: Path,
    ) {
        val forge = inbox(root.resolve("forge").also { Files.createDirectory(it) })
        val own = Files.createDirectories(root.resolve("own/cc"))
        val self = PeerSender(own.resolve("42.sock").toString(), "gary")
        val link = Files.createSymbolicLink(own.resolve("1.sock"), forge.socket)
        val socket = PeerNoteSocket(Dispatchers.IO, newId = { "r-1" })
        val addresses = listOf(
            "uds:${forge.socket}",
            "uds:$own/../../forge/cc/1.sock",
            "uds:$link",
        )
        for (address in addresses) {
            assertFalse(runBlocking { socket.receipt(address, "m-1", DeliveryStatus.DELIVERED, null, self) }, address)
        }
        assertFalse(forge.received.isDone)
    }
}
