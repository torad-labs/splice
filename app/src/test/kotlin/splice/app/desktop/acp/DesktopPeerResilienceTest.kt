// Regression tests for PR #805 review findings:
// 1. Transcript write failures settle claimed peer frames cleanly with a prompt DROPPED receipt and never hang.
// 2. Returned outbound delivery receipts (delivered / dropped) on native-inbox sockets are journaled for PeerMailbox.
// 3. Explicit malformed auth lines (non-string / missing token) are denied instead of treated as unauthenticated.
package splice.app.desktop.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.control.peer.SentRecord
import splice.app.peer.PeerMailbox
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.DesktopAcp
import splice.provider.antigravity.peer.DesktopPeerMessage
import splice.provider.antigravity.peer.DesktopPeerWiring
import splice.sessions.note.PeerNoteSocket
import splice.sessions.note.PeerWire
import splice.sessions.peer.DeliveryStatus
import splice.sessions.peer.InboundFrame
import splice.sessions.peer.PeerSender
import splice.sessions.peer.SendOutcome
import splice.sessions.peer.helper.HelperIdentity
import splice.sessions.peer.helper.PeerRegistryFiles
import splice.sessions.peer.helper.PeerSockets
import splice.sessions.peer.helper.SocketBind
import splice.sessions.peer.owner.PeerOwnerProcesses
import splice.sessions.peer.store.PeerRegistration
import splice.sessions.peer.store.PeerRequest
import splice.sessions.peer.store.PeerStore
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionClient
import splice.sessions.registry.SessionProcess
import splice.sessions.registry.SessionRecord
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import splice.sessions.registry.SessionStatus
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

class DesktopPeerResilienceTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `transcript write failure settles peer frame with prompt dropped receipt and never hangs`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-fail"))
        val home = Files.createDirectories(tmp.resolve("home-fail"))
        val project = Files.createDirectories(tmp.resolve("work-fail"))
        val sessionsDir = home.resolve(".claude/sessions")
        val env = envOf(home, runtime)
        // Put a directory where ClaudeTranscript writes s-fail-1.jsonl so Files.writeString throws IOException.
        val projectSlug = project.toString().replace(Regex("[^A-Za-z0-9-]"), "-")
        Files.createDirectories(home.resolve(".claude/projects").resolve(projectSlug).resolve("s-fail-1.jsonl"))
        val agent = project.resolve("agent.sh")
        Files.writeString(agent, openedScript("s-fail-1"))
        val sender = bindSocket(runtime, sessionsDir, 999101L, "peer-fail-sender-token")
        try {
            val broken = BrokenEnv(agent, project, env, runtime, sessionsDir)
            val receipt = sendDuringBrokenTranscript(broken, sender)
            assertEquals(DeliveryStatus.DROPPED, receipt.status)
            assertEquals("msg-tx-fail", receipt.origMsgId)
            assertTrue(receipt.detail?.contains("delivery failed") == true, receipt.detail)
        } finally {
            sender.close()
        }
    }

    @Test
    fun `journals returned outbound delivery receipts for native peer status and ignores bad-token receipts`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-rcpt"))
        val home = Files.createDirectories(tmp.resolve("home-rcpt"))
        val stateDir = Files.createDirectories(tmp.resolve("state-rcpt"))
        val settingsDir = Files.createDirectories(tmp.resolve("settings-rcpt"))
        val configDir = home.resolve(".claude")
        val clock = WallClock { 1_795_300_000_000L }
        val peerName = "agy-rcpt-peer"
        val mailbox = PeerMailbox(PeerStore(settingsDir), stateDir, LogSink {}, clock)
        seedSentRecords(mailbox, peerName, listOf("m-ok", "m-bad", "m-spoof"))
        val factory = DesktopPeerSocketFactory(
            env = envOf(home, runtime),
            io = Dispatchers.IO,
            log = LogSink {},
            clock = clock,
            config = DesktopPeerSocketConfig(runtime, DesktopPeerReceiptStore(stateDir, settingsDir, peerName)),
        )
        runBlocking {
            withTimeout(10_000L) {
                val inbox = checkNotNull(factory.open(configDir, ProcessHandle.current().pid(), "s-agy-1", this))
                try {
                    val socket = Path.of(checkNotNull(inbox.socketPath))
                    sendRaw(socket, receiptLine("m-ok", "delivered", null, "uds:/tmp/target.sock"))
                    sendRaw(socket, receiptLine("m-bad", "dropped", "busy", "uds:/tmp/target.sock"))
                    val spoof = """{"type":"auth","token":"peer-wrong-token"}""" + "\n" +
                        receiptLine("m-spoof", "delivered", null, "uds:/tmp/target.sock")
                    sendRaw(socket, spoof)
                    awaitReceipt(mailbox, peerName, "m-bad")
                } finally {
                    inbox.close()
                }
            }
        }
        val okStatus = checkNotNull(mailbox.sent(peerName, "m-ok"))
        assertEquals("delivered", JsonScalars.str(okStatus["receipt"]?.jsonObject, "status"))
        val badStatus = checkNotNull(mailbox.sent(peerName, "m-bad"))
        assertEquals("dropped", JsonScalars.str(badStatus["receipt"]?.jsonObject, "status"))
        assertEquals("busy", JsonScalars.str(badStatus["receipt"]?.jsonObject, "detail"))
        val spoofStatus = checkNotNull(mailbox.sent(peerName, "m-spoof"))
        assertTrue(spoofStatus["receipt"] is JsonNull, "bad-token receipt must not be journaled: $spoofStatus")
    }

    @Test
    fun `lazily resolves owner-bound peer to journal receipts when startup registration reply was lost`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-lazy"))
        val home = Files.createDirectories(tmp.resolve("home-lazy"))
        val stateDir = Files.createDirectories(tmp.resolve("state-lazy"))
        val settingsDir = Files.createDirectories(tmp.resolve("settings-lazy"))
        val configDir = home.resolve(".claude")
        val clock = WallClock { 1_795_300_000_000L }
        val pid = ProcessHandle.current().pid()
        val self = checkNotNull(PeerOwnerProcesses().capture(pid))
        val peerName = "agy-lazy-rcpt"
        val store = PeerStore(settingsDir)
        val reg = runBlocking {
            store.register(PeerRequest(peerName, tmp.toString(), listOf("*"), self, nativeInbox = true), emptySet())
        }
        assertTrue(reg is PeerRegistration.Registered)
        val mailbox = PeerMailbox(store, stateDir, LogSink {}, clock)
        seedSentRecords(mailbox, peerName, listOf("m-lazy"))
        val factory = DesktopPeerSocketFactory(
            env = envOf(home, runtime),
            io = Dispatchers.IO,
            log = LogSink {},
            clock = clock,
            config = DesktopPeerSocketConfig(runtime, DesktopPeerReceiptStore(stateDir, settingsDir, peerName = null)),
        )
        runBlocking {
            withTimeout(10_000L) {
                val inbox = checkNotNull(factory.open(configDir, pid, "s-agy-lazy", this))
                try {
                    val socket = Path.of(checkNotNull(inbox.socketPath))
                    sendRaw(socket, receiptLine("m-lazy", "delivered", null, "uds:/tmp/target.sock"))
                    awaitReceipt(mailbox, peerName, "m-lazy")
                } finally {
                    inbox.close()
                }
            }
        }
        val status = checkNotNull(mailbox.sent(peerName, "m-lazy"))
        assertEquals("delivered", JsonScalars.str(status["receipt"]?.jsonObject, "status"))
    }

    private fun seedSentRecords(mailbox: PeerMailbox, peerName: String, ids: List<String>) {
        val target = SessionRecord(
            sessionId = "s-target",
            name = "walk-a",
            status = SessionStatus(state = "idle"),
            route = SessionRoute.Direct,
            availability = SessionAvailability.LIVE,
            process = SessionProcess(10L, "/tmp", 1L, 1L, "/tmp/target.sock"),
            client = SessionClient("interactive", "2.1.328", peerProtocol = 1),
        )
        ids.forEach { id -> mailbox.recordSend(peerName, SentRecord(id, target, 5, SendOutcome.Submitted(id))) }
    }

    @Test
    fun `explicit malformed auth line is denied instead of treated as unauthenticated`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-auth"))
        val sessionsDir = Files.createDirectories(tmp.resolve("home-auth/.claude/sessions"))
        val sender = bindSocket(runtime, sessionsDir, 999102L, null)
        val inbox = bindSocket(runtime, sessionsDir, 999103L, null)
        try {
            for (badAuth in listOf("""{"type":"auth","token":12}""", """{"type":"auth"}""")) {
                val messages = Channel<DesktopPeerMessage>(Channel.UNLIMITED)
                val ctx = AcpPeerContext(
                    token = "peer-valid-test-token",
                    user = System.getProperty("user.name").orEmpty(),
                    sender = PeerSender(inbox.socket.toString(), "s-auth"),
                    outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(sessionsDir)),
                    messages = messages,
                    io = Dispatchers.IO,
                    log = LogSink {},
                )
                val receipt = runMalformedAuth(sender, inbox, badAuth, ctx)
                assertEquals(DeliveryStatus.DENIED, receipt.status, "expected DENIED for $badAuth")
                assertNull(messages.tryReceive().getOrNull(), "malformed auth must never enqueue message")
            }
        } finally {
            inbox.close()
            sender.close()
        }
    }

    private fun sendDuringBrokenTranscript(broken: BrokenEnv, sender: BoundSocket): InboundFrame.Receipt {
        val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, 1 shl 16)
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val stdout = lineSink(received)
        val clock = WallClock { 1_795_300_000_000L }
        val factory = DesktopPeerSocketFactory(
            broken.env,
            Dispatchers.IO,
            LogSink {},
            clock,
            config = DesktopPeerSocketConfig(runtimeOverride = broken.runtime),
        )
        val args = listOf(
            "--output-format",
            "stream-json",
            "--model=claude-antigravity--gem-4-neon",
            "--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude",
        )
        val receipt = CompletableDeferred<InboundFrame.Receipt>()
        runBlocking {
            withTimeout(15_000L) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", broken.agent.toString())
                    DesktopAcp(args, broken.project, broken.env, Dispatchers.IO, LogSink {}, clock, agy)
                        .run(stdin, stdout, peer = DesktopPeerWiring(inbox = factory))
                }
                toSession.write((RESILIENCE_INIT + "\n").toByteArray())
                toSession.flush()
                while (received.receive().str("type") != "control_response") {
                    // Wait for initialize reply before sending the peer message.
                }
                val discovered = SessionRegistry(broken.sessionsDir, RouteOfPid { SessionRoute.Direct }, clock = clock)
                    .read().single { it.sessionId == "s-fail-1" }
                val receiptJob = async(Dispatchers.IO) { readReceipt(sender.channel) }
                val outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(broken.sessionsDir))
                val sent = outbox.send(
                    discovered,
                    "trigger transcript failure",
                    PeerSender(sender.socket.toString(), "walk-a"),
                    msgId = "msg-tx-fail",
                )
                assertEquals(SendOutcome.Submitted("msg-tx-fail"), sent)
                receipt.complete(receiptJob.await())
                toSession.close()
                assertEquals(0, run.await())
            }
        }
        return runBlocking { receipt.await() }
    }

    private fun runMalformedAuth(
        sender: BoundSocket,
        inbox: BoundSocket,
        badAuth: String,
        ctx: AcpPeerContext,
    ): InboundFrame.Receipt = runBlocking {
        withTimeout(5_000L) {
            val receiptJob = async(Dispatchers.IO) { readReceipt(sender.channel) }
            val serverJob = async(Dispatchers.IO) {
                inbox.channel.accept().use { channel -> DesktopPeerConnection(channel, ctx).serve() }
            }
            val msg = """{"msgV":1,"msg_id":"msg-bad-auth","type":"user","from":"uds:${sender.socket}",""" +
                """"message":{"role":"user","content":"blocked"}}"""
            sendRaw(inbox.socket, "$badAuth\n$msg\n")
            serverJob.await()
            receiptJob.await()
        }
    }

    private suspend fun awaitReceipt(mailbox: PeerMailbox, peerName: String, msgId: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            val status = mailbox.sent(peerName, msgId)
            if (status != null && status["receipt"] !is JsonNull) return
            yield()
        }
        assertNotNull(mailbox.sent(peerName, msgId)?.get("receipt")?.takeUnless { it is JsonNull })
    }

    private fun receiptLine(origMsgId: String, status: String, detail: String?, from: String): String {
        val detailPart = detail?.let { ""","status_detail":"$it"""" }.orEmpty()
        return """{"msgV":1,"msg_id":"r-$origMsgId","type":"control","action":"peer_message_status",""" +
            """"status":"$status","orig_msg_id":"$origMsgId","from":"$from"$detailPart}""" + "\n"
    }

    private fun sendRaw(socket: Path, payload: String) {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
            client.connect(UnixDomainSocketAddress.of(socket))
            val buf = ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8))
            while (buf.hasRemaining()) client.write(buf)
            client.shutdownOutput()
        }
    }

    private fun envOf(home: Path, runtime: Path): EnvReader = EnvReader {
        when (it) {
            "HOME" -> home.toString()
            "XDG_RUNTIME_DIR" -> runtime.toString()
            else -> null
        }
    }

    private fun lineSink(received: Channel<JsonObject>): OutputStream = object : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (b != '\n'.code) {
                line.write(b)
                return
            }
            received.trySend(Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject)
            line.reset()
        }
    }

    private fun bindSocket(runtime: Path, sessionsDir: Path, pid: Long, token: String?): BoundSocket {
        val uid = (Files.getAttribute(runtime, "unix:uid") as? Int)?.toLong()
        val sockets = PeerSockets(runtime, uid = uid, user = System.getProperty("user.name"))
        val path = sockets.pathFor(pid)
        val bound = when (val attempt = sockets.bind(path)) {
            is SocketBind.Bound -> attempt
            is SocketBind.Refused -> error("bind failed: ${attempt.reason}")
        }
        if (token != null) {
            PeerRegistryFiles(listOf(sessionsDir))
                .writeKey(sessionsDir, HelperIdentity(pid, null, null, 0L), path, token)
        }
        return BoundSocket(bound.channel, path)
    }

    private fun readReceipt(server: ServerSocketChannel): InboundFrame.Receipt {
        server.configureBlocking(false)
        Selector.open().use { selector ->
            server.register(selector, SelectionKey.OP_ACCEPT)
            check(selector.select(5_000L) > 0) { "no receipt arrived" }
        }
        server.configureBlocking(true)
        server.accept().use { channel ->
            val buf = ByteBuffer.allocate(8192)
            val out = ByteArrayOutputStream()
            while (true) {
                buf.clear()
                val n = channel.read(buf)
                if (n <= 0) break
                out.write(buf.array(), 0, n)
            }
            return out.toString(Charsets.UTF_8).lines().filter(String::isNotBlank)
                .mapNotNull(PeerWire::decode).filterIsInstance<InboundFrame.Receipt>().single()
        }
    }

    private fun openedScript(sessionId: String): String {
        val sessionJson = """{"sessionId":"$sessionId","modes":{"currentModeId":"default"},"configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""
        return listOf(
            """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'""",
            """read l; echo '{"jsonrpc":"2.0","id":2,"result":$sessionJson}'""",
            "read l",
        ).joinToString("\n")
    }

    private data class BrokenEnv(
        val agent: Path,
        val project: Path,
        val env: EnvReader,
        val runtime: Path,
        val sessionsDir: Path,
    )

    private class BoundSocket(val channel: ServerSocketChannel, val socket: Path) {
        fun close() {
            channel.close()
            Files.deleteIfExists(socket)
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

private const val RESILIENCE_INIT: String =
    """{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize"}}"""
