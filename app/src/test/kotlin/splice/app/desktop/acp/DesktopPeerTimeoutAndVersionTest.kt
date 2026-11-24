// Focused tests for DesktopPeerConnection timeout settlement (no delivery after a `dropped` receipt) and
// ClaudeArgs/ClaudeSessionRecord version handling without `--stands-in-for`.
package splice.app.desktop.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.GATEWAY_VERSION
import splice.core.util.EnvReader
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
import splice.sessions.registry.RouteOfPid
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DesktopPeerTimeoutAndVersionTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `timed-out peer message expires atomically so it is never delivered after a dropped receipt`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-timeout"))
        val sessionsDir = Files.createDirectories(tmp.resolve("home-timeout/.claude/sessions"))
        val sender = bindSocket(runtime, sessionsDir, 999004L, "peer-sender-test-token")
        val inbox = bindSocket(runtime, sessionsDir, 999005L, null)
        try {
            val messages = Channel<DesktopPeerMessage>(Channel.UNLIMITED)
            val token = "peer-timeout-test-token"
            val ctx = AcpPeerContext(
                token = token,
                user = System.getProperty("user.name").orEmpty(),
                sender = PeerSender(inbox.second.toString(), "s-timeout"),
                outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(sessionsDir)),
                messages = messages,
                io = Dispatchers.IO,
                log = LogSink {},
            )
            val receipt = runTimeoutDelivery(sender, inbox, token, ctx)
            assertEquals(DeliveryStatus.DROPPED, receipt.status)
            assertEquals("msg-to-1", receipt.origMsgId)
            val queued = checkNotNull(messages.tryReceive().getOrNull())
            assertTrue(queued.isExpired, "timed-out message must be marked expired")
            assertFalse(queued.claimForDelivery(), "expired message must never be claimable for later delivery")
        } finally {
            inbox.first.close()
            Files.deleteIfExists(inbox.second)
            sender.first.close()
            Files.deleteIfExists(sender.second)
        }
    }

    @Test
    fun `keeps gateway version in system init without stands-in-for while advertising splice-acp in session record`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-ver"))
        val home = Files.createDirectories(tmp.resolve("home-ver"))
        val project = Files.createDirectories(tmp.resolve("work-ver"))
        val sessionsDir = home.resolve(".claude/sessions")
        val env = EnvReader {
            when (it) {
                "HOME" -> home.toString()
                "XDG_RUNTIME_DIR" -> runtime.toString()
                else -> null
            }
        }
        val agent = project.resolve("agent.sh")
        Files.writeString(agent, versionEchoScript())
        val (seen, recordVersion) = runWithoutStandsInFor(agent, project, env, runtime, sessionsDir)
        val init = seen.first { it.str("type") == "system" && it.str("subtype") == "init" }
        assertEquals(GATEWAY_VERSION, init.str("claude_code_version"))
        assertEquals("splice-acp/$GATEWAY_VERSION", recordVersion)
    }

    @Test
    fun `blocked desktop output timing out sends dropped and never submits session prompt to agy`() {
        val runtime = Files.createDirectories(tmp.resolve("runtime-block"))
        val home = Files.createDirectories(tmp.resolve("home-block"))
        val project = Files.createDirectories(tmp.resolve("work-block"))
        val sessionsDir = home.resolve(".claude/sessions")
        val promptMarker = project.resolve("prompt-received.txt")
        val agent = project.resolve("agent.sh")
        Files.writeString(agent, markerOnPromptScript(promptMarker))
        val sender = bindSocket(runtime, sessionsDir, 999006L, "peer-block-test-token")
        try {
            val receipt = runBlockedOutputRace(BlockedRun(agent, project, home, runtime, sessionsDir), sender)
            assertEquals(DeliveryStatus.DROPPED, receipt.status)
            assertEquals("msg-blk-1", receipt.origMsgId)
            assertFalse(
                Files.exists(promptMarker),
                "agy must never receive session/prompt after a dropped receipt was sent",
            )
        } finally {
            sender.first.close()
            Files.deleteIfExists(sender.second)
        }
    }

    private fun runBlockedOutputRace(
        runSpec: BlockedRun,
        sender: Pair<ServerSocketChannel, Path>,
    ): InboundFrame.Receipt {
        val env = blockedEnv(runSpec)
        val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, 1 shl 16)
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val outBlocked = CompletableDeferred<Unit>()
        val releaseOut = CountDownLatch(1)
        val clock = WallClock { 1_795_300_000_000L }
        val factory = DesktopPeerSocketFactory(
            env,
            Dispatchers.IO,
            LogSink {},
            clock,
            config = DesktopPeerSocketConfig(runtimeOverride = runSpec.runtime, deliveryTimeoutMs = 150L),
        )
        return runBlocking {
            withTimeout(15_000L) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", runSpec.agent.toString())
                    val stdout = blockingUserReplaySink(received, outBlocked, releaseOut)
                    DesktopAcp(blockedArgs(), runSpec.project, env, Dispatchers.IO, LogSink {}, clock, agy)
                        .run(stdin, stdout, DesktopPeerWiring(inbox = factory))
                }
                toSession.write((INIT_LINE + "\n").toByteArray())
                toSession.flush()
                while (received.receive().str("type") != "control_response") {
                    // Wait until initialize opens the session and peer UDS inbox.
                }
                val receiptJob = async(Dispatchers.IO) { readReceipt(sender.first) }
                sendBlockedFrame(runSpec.sessionsDir, sender.second, clock)
                outBlocked.await()
                val receipt = receiptJob.await()
                releaseOut.countDown()
                toSession.close()
                assertEquals(0, run.await())
                receipt
            }
        }
    }

    private suspend fun sendBlockedFrame(sessionsDir: Path, senderSocket: Path, clock: WallClock) {
        val discovered = SessionRegistry(sessionsDir, RouteOfPid { SessionRoute.Direct }, clock = clock)
            .read().single { it.sessionId == "s-blk-1" }
        val outbox = PeerNoteSocket(Dispatchers.IO, registryDirs = listOf(sessionsDir))
        val sent = outbox.send(
            discovered,
            "blocked-output message",
            PeerSender(senderSocket.toString(), "walk-a"),
            msgId = "msg-blk-1",
        )
        assertEquals(SendOutcome.Submitted("msg-blk-1"), sent)
    }

    private fun blockedEnv(runSpec: BlockedRun): EnvReader = EnvReader {
        when (it) {
            "HOME" -> runSpec.home.toString()
            "XDG_RUNTIME_DIR" -> runSpec.runtime.toString()
            else -> null
        }
    }

    private fun blockedArgs(): List<String> = listOf(
        "--output-format",
        "stream-json",
        "--model=claude-antigravity--gem-4-neon",
        "--stands-in-for=/home/me/.config/Claude/claude-code/2.1.328/10f24c9cfcbf/claude",
    )

    private fun blockingUserReplaySink(
        received: Channel<JsonObject>,
        outBlocked: CompletableDeferred<Unit>,
        releaseOut: CountDownLatch,
    ): OutputStream = object : OutputStream() {
        private val line = ByteArrayOutputStream()

        override fun write(b: Int) {
            if (b != '\n'.code) {
                line.write(b)
                return
            }
            val parsed = Json.parseToJsonElement(line.toString(Charsets.UTF_8)).jsonObject
            line.reset()
            if (parsed.str("type") == "user") {
                outBlocked.complete(Unit)
                releaseOut.await(5, TimeUnit.SECONDS)
            }
            received.trySend(parsed)
        }
    }

    private fun markerOnPromptScript(marker: Path): String {
        val sessionJson = """{"sessionId":"s-blk-1","modes":{"currentModeId":"default"},"configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""
        return listOf(
            """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'""",
            """read l; echo '{"jsonrpc":"2.0","id":2,"result":$sessionJson}'""",
            """if read l; then echo "${'$'}l" > "$marker"; fi""",
        ).joinToString("\n")
    }

    private data class BlockedRun(
        val agent: Path,
        val project: Path,
        val home: Path,
        val runtime: Path,
        val sessionsDir: Path,
    )

    private fun runTimeoutDelivery(
        sender: Pair<ServerSocketChannel, Path>,
        inbox: Pair<ServerSocketChannel, Path>,
        token: String,
        ctx: AcpPeerContext,
    ): InboundFrame.Receipt = runBlocking {
        withTimeout(5_000L) {
            val receiptJob = async(Dispatchers.IO) { readReceipt(sender.first) }
            val serverJob = async(Dispatchers.IO) {
                inbox.first.accept().use { channel ->
                    DesktopPeerConnection(channel, ctx, deliveryTimeoutMs = 50L).serve()
                }
            }
            val frame = """{"msgV":1,"msg_id":"msg-to-1","type":"user","from":"uds:${sender.second}",""" +
                """"message":{"role":"user","content":"late hello"}}"""
            val payload = """{"type":"auth","token":"$token"}""" + "\n" + frame + "\n"
            SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                client.connect(UnixDomainSocketAddress.of(inbox.second))
                val buf = ByteBuffer.wrap(payload.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) client.write(buf)
                client.shutdownOutput()
            }
            serverJob.await()
            receiptJob.await()
        }
    }

    private fun runWithoutStandsInFor(
        agent: Path,
        project: Path,
        env: EnvReader,
        runtime: Path,
        sessionsDir: Path,
    ): Pair<List<JsonObject>, String?> {
        val toSession = PipedOutputStream()
        val stdin = PipedInputStream(toSession, 1 shl 16)
        val received = Channel<JsonObject>(Channel.UNLIMITED)
        val seen = mutableListOf<JsonObject>()
        val stdout = lineSink(received)
        var recordVersion: String? = null
        val args = listOf("--output-format", "stream-json", "--model=claude-antigravity--gem-4-neon")
        val clock = WallClock { 1_795_300_000_000L }
        val factory = DesktopPeerSocketFactory(
            env,
            Dispatchers.IO,
            LogSink {},
            clock,
            config = DesktopPeerSocketConfig(runtimeOverride = runtime),
        )
        runBlocking {
            withTimeout(20_000L) {
                val run = async(Dispatchers.IO) {
                    val agy = listOf("sh", agent.toString())
                    DesktopAcp(args, project, env, Dispatchers.IO, LogSink {}, clock, agy)
                        .run(stdin, stdout, DesktopPeerWiring(inbox = factory))
                }
                toSession.write((INIT_LINE + "\n").toByteArray())
                toSession.flush()
                while (true) {
                    val line = received.receive()
                    seen += line
                    if (line.str("type") == "control_response") break
                }
                val pid = ProcessHandle.current().pid()
                val record = Json.parseToJsonElement(Files.readString(sessionsDir.resolve("$pid.json"))).jsonObject
                recordVersion = record.str("version")
                toSession.write((USER_LINE + "\n").toByteArray())
                toSession.flush()
                while (true) {
                    val line = received.receive()
                    seen += line
                    if (line.str("type") == "result") break
                }
                toSession.close()
                assertEquals(0, run.await())
            }
        }
        return seen to recordVersion
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

    private fun bindSocket(
        runtime: Path,
        sessionsDir: Path,
        pid: Long,
        token: String?,
    ): Pair<ServerSocketChannel, Path> {
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
        return bound.channel to path
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

    private fun versionEchoScript(): String {
        val sessionJson = """{"sessionId":"s-ver-1","modes":{"currentModeId":"default"},"configOptions":[""" +
            """{"id":"model","currentValue":"gemini-4-neon",""" +
            """"options":[{"value":"gemini-4-neon","name":"Gemini 4 Neon"}]}],""" +
            """"models":{"availableModels":[{"modelId":"gemini-4-neon-high"}]}}"""
        val textUpdate = """{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"ok"}}"""
        val updateJson = """{"jsonrpc":"2.0","method":"session/update",""" +
            """"params":{"sessionId":"s-ver-1","update":$textUpdate}}"""
        return listOf(
            """read l; echo '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}'""",
            """read l; echo '{"jsonrpc":"2.0","id":2,"result":$sessionJson}'""",
            "read l",
            """echo '$updateJson'""",
            """echo '{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}'""",
            "read l",
        ).joinToString("\n")
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

private const val INIT_LINE: String =
    """{"type":"control_request","request_id":"init-1","request":{"subtype":"initialize"}}"""
private const val USER_LINE: String =
    """{"type":"user","message":{"role":"user","content":"hi"},""" +
        """"parent_tool_use_id":null,"priority":"next","uuid":"u-2"}"""
