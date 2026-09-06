// NEW (2026-09-06): a silent wire carries ping EVENTS. Claude Code 2.1.257's async-agent stall
// watchdog aborts a turn after 600 s with no yielded stream event; the SSE-comment keepalive never
// reaches its parser, a ping event does (its query loop yields every one as progress). Driven
// through the REAL production path: a real HeadServer, a raw client socket, an upstream parked
// after its first delta (SCENARIO:hold), and a ticker paced at 10 ms so 15 silent ticks fit a test.
package head

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.Ticker
import splice.spi.UpstreamClient
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private class HeartbeatAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-hb", "acct-hb")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerHeartbeatTest {

    private val mock = MockChatGptUpstream()
    private val port = freshPort()
    private val gate = InflightGate({ 0 })
    private val lines = CopyOnWriteArrayList<String>()
    private lateinit var head: HeadServer

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("head-heartbeat")
        head = HeadServer(
            provider = CodexProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = HeartbeatAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(600.seconds, 600.seconds, 900.seconds),
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 600_000, totalTimeoutMs = 900_000, maxRetries = 2),
                inferenceToken = "test-inference-token",
                gate = gate,
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = { lines += it },
                // 15 silent ticks = one heartbeat; at 10 ms a tick the test sees several per second.
                ticker = Ticker {
                    delay(10)
                    true
                },
            ),
        )
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        mock.releaseHold()
        head.stop()
        mock.stop()
    }

    private val pingFrame = Regex("event: ping\\ndata: \\{\"type\":\"ping\"\\}")

    private val body = """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,""" +
        """"system":"You are a test. SCENARIO:hold","messages":[{"role":"user","content":"go"}]}"""

    /** Everything the socket has delivered so far, appended by a reader thread. */
    private fun drain(input: InputStream, into: StringBuilder) = thread(isDaemon = true) {
        val buf = ByteArray(8192)
        while (true) {
            val n = try { input.read(buf) } catch (_: IOException) { -1 }
            if (n < 0) break
            synchronized(into) { into.append(String(buf, 0, n, Charsets.UTF_8)) }
        }
    }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(20)
        }
        return cond()
    }

    @Test
    fun `a wire silent after its first delta carries ping events until the upstream resumes`() = runBlocking {
        mock.resetHold()
        val socket = Socket("127.0.0.1", port)
        val request = "POST /v1/messages HTTP/1.1\r\n" +
            "Host: 127.0.0.1:$port\r\n" +
            "Authorization: Bearer test-inference-token\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray().size}\r\n" +
            "Connection: close\r\n\r\n" + body
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        val received = StringBuilder()
        drain(socket.getInputStream(), received)
        fun text() = synchronized(received) { received.toString() }
        fun pingsAfterDelta(): Int {
            val t = text()
            val delta = t.indexOf("event: content_block_delta")
            if (delta < 0) return 0
            return pingFrame.findAll(t.substring(delta)).count()
        }
        assertTrue(
            waitFor(15_000) { text().contains("event: content_block_delta") },
            "the first delta must arrive: ${text()}",
        )
        assertTrue(
            waitFor(10_000) { pingsAfterDelta() >= 3 },
            "the silent wire must carry ping events: ${text()}",
        )
        assertEquals(1L, mock.holdRelease.count, "the upstream is still parked: the pings are the proxy's own")
        mock.releaseHold()
        assertTrue(
            waitFor(15_000) { text().contains("event: message_stop") },
            "the turn still ends cleanly: ${text()}",
        )
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 0 })
        socket.close()
        assertTrue(lines.any { it.contains("perf outcome=ok") }, lines.joinToString())
    }
}
