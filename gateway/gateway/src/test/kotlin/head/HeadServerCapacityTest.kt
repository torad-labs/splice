// NEW (G21): HTTP-level admission test — a real HeadServer wired with a bounded InflightGate
// (maxInflight=1, maxQueued=1). Fills the one inflight slot, fills the one queue spot, then
// proves the THIRD concurrent request is shed with a 529 "gateway at capacity" and the exact
// Anthropic error shape, instead of growing the waiter queue without limit.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
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
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private class CapacityFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-cap", "acct-cap")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerCapacityTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)

    // Ephemeral port (HeadServerLoadTest convention): a hardcoded port BindExceptions when a
    // prior run's socket is still in TIME_WAIT.
    private val port = ServerSocket(0).use { it.localPort }
    private lateinit var head: HeadServer
    private val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() = runBlocking {
        tmp = Files.createTempDirectory("head-cap")
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = CapacityFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
                gate = gate,
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = {},
            ),
        )
        head.start()
        Thread.sleep(700) // Netty warmup
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(100)
        }
        return cond()
    }

    private suspend fun idleTurn(): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:idle",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    private suspend fun rejectedTurn(): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:idle",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    @Test
    fun `third concurrent request is shed with a 529 at capacity`() = runBlocking {
        val first = async(Dispatchers.IO) { idleTurn() }
        assertTrue(waitFor(5_000) { gate.snapshot().inflight == 1 }, "expected the first turn to hold the one slot")

        val second = async(Dispatchers.IO) { idleTurn() }
        assertTrue(
            waitFor(5_000) { gate.snapshot().queued == 1 },
            "expected the second turn to fill the one queue spot",
        )

        val response = rejectedTurn()
        assertEquals(529, response.status.value)
        val body = response.bodyAsText()
        assertTrue(body.contains("overloaded_error"), "expected overloaded_error in: $body")
        assertTrue(body.contains("gateway at capacity"), "expected 'gateway at capacity' in: $body")
        assertEquals(
            """{"type":"error","error":{"type":"overloaded_error","message":"gateway at capacity"}}""",
            body,
        )

        // let the idle scenario's own teardown (its 5s sleep, then a natural stream end) release
        // both held requests before the test returns, so @AfterAll teardown is clean.
        listOf(first, second).forEach { it.await() }
    }
}
