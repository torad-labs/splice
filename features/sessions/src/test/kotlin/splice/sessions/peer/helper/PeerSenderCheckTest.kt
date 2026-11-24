// What a journal-backed recipient, a Codex peer's or a webhook peer's, records about who a message is from. A Claude
// Code peer's send names two sockets: its unlisted return helper as the frame's `from`, where receipts go, and its
// session's own as the envelope's, where replies go. The recipient checks the reply address against the registry.
package splice.sessions.peer.helper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerTargets
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.journal.PeerInbox
import splice.sessions.peer.journal.PeerJournal
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionListing
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionSource
import splice.sessions.registry.SessionStatus
import java.nio.file.Files
import java.nio.file.Path

// why: a journal append follows the frame within milliseconds; this is how often the test looks for it.
private const val POLL_MS = 10L

// why: a deadline for a local socket round trip, not a duration the test waits.
private const val DEADLINE_MS = 5_000L

class PeerSenderCheckTest {
    private val log = LogSink { }
    private val user = System.getProperty("user.name")

    /** The sockets one send touches, short enough for sun_path under any temp folder. */
    private class Sockets(dir: Path) {
        val folder: Path = dir.resolve("s")
        val returnHelper: Path = folder.resolve("ret.sock")
        val session: Path = folder.resolve("ses.sock")
        val recipient: Path = folder.resolve("rcp.sock")
        val stranger: Path = folder.resolve("str.sock")
    }

    @Test
    fun `a Claude Code peer's send is recorded as its registered session with its session id`(@TempDir dir: Path) {
        val sockets = Sockets(dir)
        val session = sockets.session.toString()
        val sender = PeerSender(sockets.returnHelper.toString(), "caller-peer", replySocket = session)

        val from = receive(dir, sockets, listOf(sender)).single()

        assertEquals("registered", JsonScalars.str(from, "check"), from.toString())
        assertEquals("caller-id", JsonScalars.str(from, "sessionId"))
        assertEquals("caller", JsonScalars.str(from, "name"))
        assertEquals("uds:${sockets.returnHelper}", JsonScalars.str(from, "address"))
        assertEquals("uds:${sockets.session}", JsonScalars.str(from, "replyTo"))
    }

    @Test
    fun `a send whose reply address nobody registered is recorded as unregistered`(@TempDir dir: Path) {
        val sockets = Sockets(dir)
        val stranger = sockets.stranger.toString()
        val nobody = PeerSender(stranger, "caller")
        val borrowed = PeerSender(sockets.session.toString(), "caller", replySocket = stranger)

        val from = receive(dir, sockets, listOf(nobody, borrowed))

        assertEquals(listOf("unregistered", "unregistered"), from.map { JsonScalars.str(it, "check") })
        assertEquals(listOf(null, null), from.map { JsonScalars.str(it, "sessionId") })
    }

    /** The journal's `from` for each of [senders]' messages to a recipient whose registry lists only the caller's
     *  session, on [Sockets.session]. */
    private fun receive(dir: Path, sockets: Sockets, senders: List<PeerSender>): List<JsonObject> = runBlocking {
        val peers = PeerSockets(dir, null, user)
        val channel = assertInstanceOf(SocketBind.Bound::class.java, peers.bind(sockets.recipient)).channel
        val journal = PeerJournal(Files.createDirectories(dir.resolve("journal")), log)
        val wire = PeerNoteSocket(Dispatchers.IO)
        val inbox = PeerInbox(journal, wire, PeerSender(sockets.recipient.toString(), "codex-peer"), log)
        val caller = record("caller-id", "caller", sockets.session)
        val targets = PeerTargets(object : SessionSource {
            override fun read(): List<SessionRecord> = listOf(caller)

            override fun list(): SessionListing = SessionListing(read())
        })
        val helperLog = HelperLog(dir.resolve("helper.log"), WallClock(System::currentTimeMillis))
        val target = record("codex-id", "codex-peer", sockets.recipient)
        try {
            coroutineScope {
                val server = PeerInboxServer(channel, "token", inbox, targets, helperLog, Dispatchers.IO, user)
                val serving = launch { server.serve() }
                try {
                    senders.forEachIndexed { n, sender ->
                        assertInstanceOf(SendOutcome.Submitted::class.java, wire.send(target, "m$n", sender, "id-$n"))
                    }
                    withTimeout(DEADLINE_MS) {
                        while (messages(journal, senders.size).size < senders.size) delay(POLL_MS)
                    }
                    messages(journal, senders.size).map { it.getValue("from").jsonObject }
                } finally {
                    serving.cancel()
                }
            }
        } finally {
            channel.close()
        }
    }

    private fun messages(journal: PeerJournal, limit: Int) = journal.read(0, limit, setOf("message")).items

    private fun record(id: String, name: String, socket: Path) = SessionRecord(
        sessionId = id,
        name = name,
        status = SessionStatus(state = "idle"),
        route = SessionRoute.Direct,
        availability = SessionAvailability.LIVE,
        process = SessionProcess(
            pid = ProcessHandle.current().pid(),
            cwd = "/tmp",
            startedAt = 1L,
            updatedAt = 1L,
            messagingSocketPath = socket.toString(),
        ),
        client = SessionClient(kind = "interactive", version = "2.1.328", peerProtocol = 1),
    )
}
