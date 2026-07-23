// NEW (review 2026-07-23): HTTP-level integration coverage the PR review flagged as missing —
//   E: a waiter promoted from the InflightGate queue DURING a stop/restart drain is bounced with
//      the 529 "head is stopping — retry" shape (never starts a doomed upstream turn);
//   G: count_tokens fast-fails 529 "gateway busy — retry" when the materialization gate is saturated
//      (proves the HTTP route is wired to tryWithLease, not just the primitive);
//   I: upstream x-ratelimit-* headers survive to durable UsageStore state through TurnDriver.
// Each test builds an ISOLATED head so it can stop/restart/hold without disturbing a shared one.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
import splice.gateway.head.RequestMaterializationGate
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.codex.CodexProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class ReviewFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-rev", "acct-rev")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerReviewTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var tmp: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("head-review")
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun buildHead(
        port: Int,
        gate: InflightGate,
        matGate: RequestMaterializationGate,
        ratelimitFile: Path,
    ): HeadServer {
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = ReviewFakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        return HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
                inferenceToken = "test-inference-token",
                gate = gate,
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact-$port.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage-$port.json"), ratelimitFile),
                perfStats = PerfStats(tmp.resolve("perf-$port.jsonl")),
                log = {},
                requestMaterializationGate = matGate,
            ),
        )
    }

    private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

    private suspend fun waitFor(capMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            delay(50)
        }
        return cond()
    }

    private suspend fun turn(port: Int, scenario: String): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }

    private suspend fun countTokens(port: Int): HttpResponse =
        client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody("""{"model":"claude-codex--gpt-5.6-sol","messages":[{"role":"user","content":"estimate"}]}""")
        }

    @Test
    fun `a waiter promoted during the stop drain is bounced with 529 head-is-stopping`() = runBlocking {
        val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
        val port = freshPort()
        val head = buildHead(port, gate, RequestMaterializationGate(), tmp.resolve("rl-e.json"))
        head.start()
        Thread.sleep(700)
        try {
            // req1 holds the one inflight slot until we release the mock latch.
            val req1 = async(Dispatchers.IO) { turn(port, "hold").bodyAsText() }
            assertTrue(waitFor(5_000) { gate.snapshot().inflight == 1 }, "req1 should hold the slot")
            // req2 passes the accepting front-door (still accepting), then queues on the full gate.
            val req2 = async(Dispatchers.IO) { turn(port, "basic") }
            assertTrue(waitFor(5_000) { gate.snapshot().queued == 1 }, "req2 should fill the queue")

            // Restart flips accepting=false and drains; give it a beat to set the flag, THEN release
            // req1 so req2 is promoted DURING the drain — it must be bounced, not run.
            val restart = async(Dispatchers.IO) { head.restart() }
            delay(400)
            mock.holdRelease.countDown()

            val resp = req2.await()
            assertEquals(529, resp.status.value)
            val body = resp.bodyAsText()
            assertTrue(body.contains("overloaded_error"), "expected overloaded_error in: $body")
            assertTrue(body.contains("head is stopping"), "expected 'head is stopping — retry' in: $body")

            req1.await()
            restart.await()
        } finally {
            head.stop()
        }
    }

    @Test
    fun `count_tokens 529s with the busy shape when the materialization gate is saturated`() = runBlocking {
        val matGate = RequestMaterializationGate(maxConcurrent = 1)
        val port = freshPort()
        val head = buildHead(port, InflightGate(maxInflight = { 4 }), matGate, tmp.resolve("rl-g.json"))
        head.start()
        Thread.sleep(700)
        try {
            // Hold the sole materialization permit so count_tokens' fast-fail lease is contended.
            val acquired = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) {
                matGate.withLease {
                    acquired.complete(Unit)
                    release.await()
                }
            }
            acquired.await()

            val resp = countTokens(port)
            assertEquals(529, resp.status.value)
            val body = resp.bodyAsText()
            assertTrue(body.contains("overloaded_error"), "expected overloaded_error in: $body")
            assertTrue(body.contains("gateway busy"), "expected 'gateway busy — retry' in: $body")

            release.complete(Unit)
            holding.await()
            // Permit free again → count_tokens succeeds.
            assertEquals(200, countTokens(port).status.value)
        } finally {
            head.stop()
        }
    }

    @Test
    fun `upstream rate-limit headers survive to durable UsageStore state`() = runBlocking {
        val ratelimitFile = tmp.resolve("rl-i.json")
        val port = freshPort()
        val head = buildHead(port, InflightGate(maxInflight = { 4 }), RequestMaterializationGate(), ratelimitFile)
        head.start()
        Thread.sleep(700)
        try {
            // A successful turn whose response carries x-ratelimit-* headers.
            assertEquals(200, turn(port, "ratelimit").status.value)
            // head.stop() flushes pending rate-limit state durably to the file.
        } finally {
            head.stop()
        }
        // A FRESH store reading the same file must see the persisted headers — proves the TurnDriver
        // call site, not just persistRateLimit() in isolation.
        val reread = UsageStore(tmp.resolve("usage-reread.json"), ratelimitFile).readRateLimit()
        assertNotNull(reread, "rate-limit state should be durable across a fresh store")
        assertEquals(5000, reread!!.limitTokens)
        assertEquals(1200, reread.remainingTokens)
        assertEquals("6m0s", reread.resetTokens)
    }

    @Test
    fun `a stream torn before any client frame ends as one honest error, not a truncated 200`() =
        runBlocking {
            val port = freshPort()
            val head = buildHead(
                port,
                InflightGate(maxInflight = { 4 }),
                RequestMaterializationGate(),
                tmp.resolve("rl-h.json"),
            )
            head.start()
            Thread.sleep(700)
            try {
                val before = mock.upstreamBodies.count { it.first == "tear" }
                val resp = turn(port, "tear")
                val body = resp.bodyAsText()
                // The 200 + SSE headers are already committed once respondTextWriter opens, so the
                // upstream tear MUST become an honest `event: error` frame (StreamTornBeforeClient ->
                // emitConnReset in TurnDriver), never an escaped/truncated 200 with no terminal.
                assertEquals(200, resp.status.value)
                assertTrue(body.contains("event: error"), "expected an error event in: $body")
                assertTrue(body.contains("overloaded_error"), "expected overloaded_error in: $body")
                assertEquals(1, body.split("event: error").size - 1, "exactly one error event: $body")
                assertTrue(!body.contains("message_stop"), "a torn turn must NOT emit message_stop: $body")
                // Upstream was hit (the request handed off before tearing). NB: this premature-EOF
                // tear is not classed as a retryable transport reset, so it does not consume the
                // stream-reissue budget — a real connection RST (which the in-process mock cannot
                // force) would reissue up to MAX_STREAM_REISSUES; the honest-terminal mapping under
                // test here is identical either way.
                assertTrue(mock.upstreamBodies.count { it.first == "tear" } > before, "upstream was attempted")
            } finally {
                head.stop()
            }
        }
}
