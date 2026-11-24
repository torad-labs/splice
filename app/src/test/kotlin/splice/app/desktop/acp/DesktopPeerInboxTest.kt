// Integration tests for Desktop Antigravity's in-process peer UDS inbox (DesktopPeerSocketFactory + DesktopAcp) and
// automatic outbound peer identity (DesktopPeerIdentity). Exercises real Unix domain sockets, key files, SO_PEERCRED,
// SessionRegistry discovery, PeerNoteSocket delivery, PeerWire receipts (delivered / denied / dropped), and cleanup.
package splice.app.desktop.acp

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.core.util.JsonScalars
import splice.core.util.LogSink
import splice.core.util.WallClock
import splice.provider.antigravity.DesktopAcp
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
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionAvailability
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class DesktopPeerInboxTest {
    @TempDir
    lateinit var tmp: Path

    private val sessionJson =
        """{"sessionId":"s-peer-1","modes":{"currentModeId":"default"},"configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""

    private val hello = """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'"""
    private val opened = "$hello\nread l; echo '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$sessionJson}'"

    @Test
    fun `delivers authenticated peer messages to agy, replays to Desktop, sends delivered receipt, and cleans up`() {
        val textUpdate = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"'${'$'}said'"}}"""
        val updateJson = """{"jsonrpc":"2.0","method":"session/update",""" +
            """"params":{"sessionId":"s-peer-1","update":$textUpdate}}"""
        val prompt = listOf(
            "read l",
            """case "${'$'}l" in *'ping from claude'*) said=got-peer ;; *) said=missed ;; esac""",
            """echo '$updateJson'""",
            """echo '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'""",
            "read l",
        ).joinToString("\n")
        val env = sessionLayout("deliver")
        val sender = bindSender(env, 999001L, "peer-deliver-test-token")
        var boundSocket: Path? = null
        var keyFile: Path? = null
        try {
            val receipt = CompletableDeferred<InboundFrame.Receipt>()
            val seen = runSession(SessionSpec("$opened\n$prompt", env)) { scope ->
                send(INIT_REQUEST)
                next { it.str("type") == "control_response" }
                val pair = verifyPeerFilesAndDiscovery(env)
                boundSocket = pair.first
                keyFile = pair.second
                val discovered = SessionRegistry(
                    env.sessionsDir,
                    RouteOfPid { SessionRoute.Direct },
                    clock = WallClock { 1_795_300_000_000L },
                ).read().single { it.sessionId == "s-peer-1" }
                val receiptJob = scope.async(Dispatchers.IO) { readOneReceipt(sender.channel) }
                val outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(env.sessionsDir))
                val outcome = outbox.send(
                    discovered,
                    "ping from claude",
                    PeerSender(sender.socket.toString(), "walk-a"),
                    msgId = "msg-peer-1",
                )
                assertEquals(SendOutcome.Submitted("msg-peer-1"), outcome)
                next { it.str("type") == "result" }
                receipt.complete(receiptJob.await())
            }
            verifyDeliveredOutcome(seen, receipt, env, checkNotNull(boundSocket), checkNotNull(keyFile))
        } finally {
            sender.close()
        }
    }

    @Test
    fun `rejects wrong peer token with a denied receipt and never forwards to agy`() {
        val env = sessionLayout("deny")
        val sender = bindSender(env, 999002L, null)
        try {
            val receipt = CompletableDeferred<InboundFrame.Receipt>()
            val seen = runSession(SessionSpec("$opened\nread l", env)) { scope ->
                send(INIT_REQUEST)
                next { it.str("type") == "control_response" }
                val pid = ProcessHandle.current().pid()
                val record = Json.parseToJsonElement(Files.readString(env.sessionsDir.resolve("$pid.json"))).jsonObject
                val socket = Path.of(checkNotNull(record.str("messagingSocketPath")))
                val receiptJob = scope.async(Dispatchers.IO) { readOneReceipt(sender.channel) }
                val envelope = "<cross-session-message from-name=\\\"evil\\\">\\nhi\\n</cross-session-message>"
                val msg = """{"msgV":1,"msg_id":"msg-bad-1","type":"user","from":"uds:${sender.socket}",""" +
                    """"message":{"role":"user","content":"$envelope"}}"""
                val badPayload = """{"type":"auth","token":"peer-wrong-test-token"}""" + "\n" + msg + "\n"
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socket))
                    val buf = ByteBuffer.wrap(badPayload.toByteArray(Charsets.UTF_8))
                    while (buf.hasRemaining()) channel.write(buf)
                    channel.shutdownOutput()
                }
                receipt.complete(receiptJob.await())
            }
            val denied = runBlocking { withTimeout(5_000L) { receipt.await() } }
            assertEquals(DeliveryStatus.DENIED, denied.status)
            assertEquals("msg-bad-1", denied.origMsgId)
            assertTrue(seen.none { it.str("type") == "user" }, "denied message must not be replayed")
        } finally {
            sender.close()
        }
    }

    @Test
    fun `sends a dropped receipt when agy is unusable`() {
        val env = sessionLayout("drop")
        val sender = bindSender(env, 999003L, "peer-drop-test-token")
        try {
            val receipt = CompletableDeferred<InboundFrame.Receipt>()
            val authErr = """{"jsonrpc":"2.0","id":2,"error":{"code":-32000,"message":"Authentication required"}}"""
            val signedOut = "$hello\nread l; echo '$authErr'\nread l"
            runSession(SessionSpec(signedOut, env)) { scope ->
                send(INIT_REQUEST)
                next { it.str("type") == "control_response" }
                val discovered = SessionRegistry(
                    env.sessionsDir,
                    RouteOfPid { SessionRoute.Direct },
                    clock = WallClock { 1_795_300_000_000L },
                ).read().single()
                val receiptJob = scope.async(Dispatchers.IO) { readOneReceipt(sender.channel) }
                val outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(env.sessionsDir))
                val out = outbox.send(
                    discovered,
                    "hello while signed out",
                    PeerSender(sender.socket.toString(), "walk-a"),
                    msgId = "msg-drop-1",
                )
                assertEquals(SendOutcome.Submitted("msg-drop-1"), out)
                receipt.complete(receiptJob.await())
            }
            val dropped = runBlocking { withTimeout(5_000L) { receipt.await() } }
            assertEquals(DeliveryStatus.DROPPED, dropped.status)
            assertEquals("msg-drop-1", dropped.origMsgId)
        } finally {
            sender.close()
        }
    }

    @Test
    fun `auto-registers an owner-bound peer and injects SPLICE_PEER_NAME and SPLICE_PEER_KEY_FILE into agy`() {
        val state = Files.createDirectories(tmp.resolve("state"))
        val config = tmp.resolve("splice.toml")
        Files.writeString(state.resolve("mgmt-key"), "test-mgmt-key")
        Files.writeString(config, "[daemon]\nstate_dir = \"${state.toAbsolutePath()}\"\n")
        val keyFile = tmp.resolve("peers/agy-9d7c1d2e/peer.key")
        val injected = verifyRegistrationRequest(config, state, keyFile)
        verifyChildEnvDeliveredToAgy(injected, keyFile)
        val closed = TestPorts.reserve()
        val downEnv = daemonEnv(config, state, closed)
        assertEquals(emptyMap<String, String>(), DesktopPeerIdentity(downEnv, LogSink {}).childEnv(emptyList(), tmp))
    }

    private fun verifyRegistrationRequest(config: Path, state: Path, keyFile: Path): Map<String, String> {
        var seenBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/peers") { exchange ->
            seenBody = exchange.requestBody.readBytes().decodeToString()
            val reply = """{"name":"agy-9d7c1d2e","sessionId":"s-1","keyFile":"$keyFile","socket":null,"pid":null}"""
                .encodeToByteArray()
            exchange.sendResponseHeaders(201, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        return try {
            val env = daemonEnv(config, state, server.address.port)
            val project = Files.createDirectories(tmp.resolve("proj"))
            val injected = DesktopPeerIdentity(env, LogSink {}).childEnv(
                listOf("--session-id=9d7c1d2e-0000-4000-8000-000000000001"),
                project,
            )
            assertEquals("agy-9d7c1d2e", injected["SPLICE_PEER_NAME"])
            assertEquals(keyFile.toString(), injected["SPLICE_PEER_KEY_FILE"])
            val posted = Json.parseToJsonElement(seenBody).jsonObject
            assertEquals("agy-9d7c1d2e", JsonScalars.str(posted, "name"))
            assertEquals(true, posted["nativeInbox"]?.jsonPrimitive?.booleanOrNull)
            val owner = posted["owner"]?.jsonObject
            assertNotNull(owner, "owner must be bound to live splice desktop acp process")
            assertEquals(ProcessHandle.current().pid().toString(), JsonScalars.str(owner, "pid"))
            injected
        } finally {
            server.stop(0)
        }
    }

    private fun verifyChildEnvDeliveredToAgy(injected: Map<String, String>, keyFile: Path) {
        val envUpdate = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text",""" +
            """"text":"'${'$'}SPLICE_PEER_NAME'|'${'$'}SPLICE_PEER_KEY_FILE'"}}"""
        val updateLine = """{"jsonrpc":"2.0","method":"session/update",""" +
            """"params":{"sessionId":"s-peer-1","update":$envUpdate}}"""
        val envEchoScript = listOf(
            opened,
            "read l",
            """echo '$updateLine'""",
            """echo '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'""",
            "read l",
        ).joinToString("\n")
        val layout = sessionLayout("env")
        val userMsg = """{"type":"user","message":{"role":"user","content":"who"},""" +
            """"parent_tool_use_id":null,"priority":"next","uuid":"u-1"}"""
        val lines = runSession(SessionSpec(envEchoScript, layout, childEnv = injected)) {
            send(userMsg)
            next { it.str("type") == "result" }
        }
        val said = lines.single { it.str("type") == "assistant" }.obj("message")
            .getValue("content").jsonArray.single().jsonObject.str("text")
        assertEquals("agy-9d7c1d2e|$keyFile", said)
    }

    private fun verifyPeerFilesAndDiscovery(layout: SessionLayout): Pair<Path, Path> {
        val pid = ProcessHandle.current().pid()
        val recordFile = layout.sessionsDir.resolve("$pid.json")
        assertTrue(Files.isRegularFile(recordFile), "session record should exist after initialize")
        val record = Json.parseToJsonElement(Files.readString(recordFile)).jsonObject
        val socketStr = checkNotNull(record.str("messagingSocketPath")) { "messagingSocketPath missing" }
        assertEquals(1, record["peerProtocol"]?.jsonPrimitive?.int)
        assertEquals("2.1.328", record.str("version"))
        val socket = Path.of(socketStr)
        assertTrue(Files.exists(socket), "bound UDS socket must exist")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parent)))
        val peerKey = PeerRegistryFiles(listOf(layout.sessionsDir)).key(layout.sessionsDir, pid, socket)
        assertTrue(Files.isRegularFile(peerKey), "peer key file must exist beside session record")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(peerKey)))
        val discovered = SessionRegistry(
            layout.sessionsDir,
            RouteOfPid { SessionRoute.Direct },
            clock = WallClock { 1_795_300_000_000L },
        ).read().single { it.sessionId == "s-peer-1" }
        assertEquals(SessionAvailability.LIVE, discovered.availability)
        assertEquals(socketStr, discovered.process.messagingSocketPath)
        assertEquals(1, discovered.client.peerProtocol)
        return socket to peerKey
    }

    private fun verifyDeliveredOutcome(
        seen: List<JsonObject>,
        receipt: CompletableDeferred<InboundFrame.Receipt>,
        layout: SessionLayout,
        boundSocket: Path,
        keyFile: Path,
    ) {
        val delivered = runBlocking { withTimeout(5_000L) { receipt.await() } }
        assertEquals(DeliveryStatus.DELIVERED, delivered.status)
        assertEquals("msg-peer-1", delivered.origMsgId)
        val replayedText = seen.first { it.str("type") == "user" }.obj("message").str("content").orEmpty()
        assertTrue("<cross-session-message" in replayedText, replayedText)
        assertTrue("from-name=\"walk-a\"" in replayedText, replayedText)
        assertTrue("ping from claude" in replayedText, replayedText)
        val replyText = seen.single { it.str("type") == "assistant" }.obj("message")
            .getValue("content").jsonArray.single().jsonObject.str("text")
        assertEquals("got-peer", replyText)
        val pid = ProcessHandle.current().pid()
        assertFalse(Files.exists(layout.sessionsDir.resolve("$pid.json")), "session record cleaned up on exit")
        assertFalse(Files.exists(keyFile), "peer key file cleaned up on exit")
        assertFalse(Files.exists(boundSocket), "peer UDS socket cleaned up on exit")
    }

    private fun sessionLayout(tag: String): SessionLayout {
        val runtime = Files.createDirectories(tmp.resolve("runtime-sockets-$tag"))
        val home = Files.createDirectories(tmp.resolve("home-$tag"))
        val project = Files.createDirectories(tmp.resolve("work-$tag"))
        val sessionsDir = home.resolve(".claude").resolve("sessions")
        val env = EnvReader { name ->
            when (name) {
                "HOME" -> home.toString()
                "XDG_RUNTIME_DIR" -> runtime.toString()
                else -> null
            }
        }
        return SessionLayout(runtime, project, sessionsDir, env)
    }

    private fun bindSender(layout: SessionLayout, pid: Long, token: String?): SenderSocket {
        val user = System.getProperty("user.name")
        val uid = (Files.getAttribute(layout.runtime, "unix:uid") as? Int)?.toLong()
        val sockets = PeerSockets(layout.runtime, uid = uid, user = user)
        val path = sockets.pathFor(pid)
        val bound = when (val attempt = sockets.bind(path)) {
            is SocketBind.Bound -> attempt
            is SocketBind.Refused -> error("failed to bind sender socket $path: ${attempt.reason}")
        }
        if (token != null) {
            PeerRegistryFiles(listOf(layout.sessionsDir))
                .writeKey(layout.sessionsDir, HelperIdentity(pid, null, null, 0L), path, token)
        }
        return SenderSocket(bound.channel, path)
    }

    private fun daemonEnv(config: Path, state: Path, port: Int): EnvReader = EnvReader { name ->
        when (name) {
            "SPLICE_CONFIG" -> config.toString()
            "SPLICE_STATE_DIR" -> state.toString()
            "SPLICE_CONTROL_PORT" -> port.toString()
            else -> null
        }
    }

    private fun readOneReceipt(server: ServerSocketChannel): InboundFrame.Receipt {
        server.configureBlocking(false)
        java.nio.channels.Selector.open().use { selector ->
            server.register(selector, java.nio.channels.SelectionKey.OP_ACCEPT)
            val ready = selector.select(5_000L)
            check(ready > 0) { "no receipt arrived on ${server.localAddress} within 5s" }
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
            val lines = out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            return lines.mapNotNull(PeerWire::decode).filterIsInstance<InboundFrame.Receipt>().single()
        }
    }

    private fun runSession(spec: SessionSpec, drive: suspend TestDriver.(CoroutineScope) -> Unit): List<JsonObject> {
        val agent = spec.layout.project.resolve("agent.sh")
        Files.writeString(agent, spec.script)
        val args = buildList {
            add("--output-format")
            add("stream-json")
            add("--model=claude-antigravity--gem-4-neon")
            add("--permission-mode=default")
            if (spec.standsInFor) add("--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude")
        }
        val driver = TestDriver()
        runBlocking {
            withTimeout(20_000L) {
                val factory = DesktopPeerSocketFactory(
                    env = spec.layout.env,
                    io = Dispatchers.IO,
                    log = LogSink {},
                    clock = WallClock { 1_795_300_000_000L },
                    config = DesktopPeerSocketConfig(runtimeOverride = spec.layout.runtime),
                )
                val peer = DesktopPeerWiring(inbox = factory, childEnv = spec.childEnv)
                val run = async(Dispatchers.IO) {
                    DesktopAcp(
                        args = args,
                        cwd = spec.layout.project,
                        env = spec.layout.env,
                        io = Dispatchers.IO,
                        log = LogSink {},
                        clock = WallClock { 1_795_300_000_000L },
                        agy = listOf("sh", agent.toString()),
                    ).run(driver.stdin, driver.stdout, peer = peer)
                }
                driver.drive(this)
                driver.close()
                assertEquals(0, run.await())
            }
        }
        return driver.seen
    }

    private data class SessionLayout(val runtime: Path, val project: Path, val sessionsDir: Path, val env: EnvReader)

    private data class SessionSpec(
        val script: String,
        val layout: SessionLayout,
        val childEnv: Map<String, String> = emptyMap(),
        val standsInFor: Boolean = true,
    )

    private class SenderSocket(val channel: ServerSocketChannel, val socket: Path) {
        fun close() {
            channel.close()
            Files.deleteIfExists(socket)
        }
    }

    private class TestDriver {
        private val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, 1 shl 16)
        private val received = Channel<JsonObject>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val stdout: OutputStream = object : OutputStream() {
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

        fun send(line: String) {
            toSession.write((line + "\n").toByteArray())
            toSession.flush()
        }

        suspend fun next(wanted: (JsonObject) -> Boolean): List<JsonObject> {
            while (true) {
                val line = received.receive()
                seen += line
                if (wanted(line)) return seen
            }
        }

        fun close() = toSession.close()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
}

private const val INIT_REQUEST: String =
    """{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize"}}"""
