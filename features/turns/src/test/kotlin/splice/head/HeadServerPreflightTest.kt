// NEW: V4-446 — HTTP preflight overflow beside the existing SSE overflow integration test.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.CodexCompactionReserves
import splice.core.model.DiscoveredModel
import splice.core.model.ExtraWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.InputDigest
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.head.perf.PerfRowMeta
import splice.head.perf.PerfStats
import splice.head.wire.TraceStore
import splice.upstream.ProviderTuning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class PreflightAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("synthetic-token", "synthetic-account")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

class HeadServerPreflightTest {
    internal fun head(
        root: Path,
        upstream: MockChatGptUpstream,
        window: Long = 272_000,
        stats: PerfStats = PerfStats(root.resolve("perf-preflight.jsonl")),
        model: String = "gpt-5.6-sol",
        servedWindow: Long? = null,
    ): HeadServer {
        val catalog = if (servedWindow != null) {
            ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = upstream.baseUrl,
                auth = AuthConfig("chatgpt-oauth"),
                extraWindows = listOf(ExtraWindow(model, window)),
            ).catalogFor(
                HeadConfig("codex", 3101, "claude-codex--", model),
                discovered = listOf(DiscoveredModel(model, contextWindow = servedWindow)),
            )
        } else {
            ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry(model, contextWindow = window)),
                defaultContextWindow = window,
                pinnedModel = model,
                compactionReserveDefaults = CodexCompactionReserves,
            )
        }
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = model,
                auth = PreflightAuth(),
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
        )
        val trace = TraceStore(ActivityDays(root.resolve("trace"), "codex", 7, ownerOnly = true), "codex", 4_096)
        val stores = headStores(root, suffix = "-preflight").copy(trace = trace, perfStats = stats)
        return HeadServer(provider, 0, headDeps(root).copy(stores = stores))
    }

    internal suspend fun send(
        client: HttpClient,
        port: Int,
        previous: String,
        content: String,
        system: String = "test",
        model: String = "gpt-5.6-sol",
    ): HttpResponse = client.post("http://127.0.0.1:$port/v1/messages") {
        header("Content-Type", "application/json")
        header("x-claude-code-session-id", "preflight-session")
        val body = """{"model":"claude-codex--$model","stream":true,"max_tokens":64,"system":"$system","messages":[""" +
            previous + """{"role":"user","content":"$content"}]}"""
        setBody(body)
    }

    private suspend fun compact(client: HttpClient, port: Int, previous: String, content: String): HttpResponse =
        send(client, port, previous, content, "SCENARIO:basic tasked with summarizing conversations")

    private fun measured(
        stats: PerfStats,
        upstream: MockChatGptUpstream,
        firstText: String,
        tokens: Long,
        model: String = "gpt-5.6-sol",
    ) {
        val request = Json.parseToJsonElement(upstream.upstreamBodies.last().second).jsonObject
        val perf = TurnPerf { 0L }.apply { setCount(PerfKeys.IN_TOKENS, tokens) }
        stats.measuredInputs.remember(
            PerfRowMeta(
                model = model,
                outcome = "ok",
                compact = false,
                sessionId = "preflight-session",
                conversationKey = "splice-" + InputDigest.hex(firstText).take(32),
            ),
            perf.snapshot(),
            request,
        )
    }

    private suspend fun awaitRows(stats: PerfStats, count: Int) {
        withTimeout(5_000) {
            while (stats.tailNumeric(count).size < count) {
                check(AsyncFileIo.drain())
                yield()
            }
        }
    }

    private fun assertRecovery(message: String, inputTokens: Long, window: Long) {
        assertTrue("$inputTokens input tokens" in message && "$window-token window" in message, message)
        assertTrue("larger context window" in message && "start a fresh conversation" in message, message)
    }

    internal fun recorded(root: Path): String {
        check(AsyncFileIo.drain()) { "trace writes must settle" }
        return Files.list(root.resolve("trace")).use { paths ->
            paths.filter { it.toString().endsWith(".jsonl") }.map(Files::readString).toList().joinToString("\n")
        }
    }

    @Test
    fun `a fresh daemon never refuses unmeasured large ordinary or compact input`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val server = head(root, upstream, window = 872_000)
        try {
            server.start()
            val history = "x".repeat(3_000_000)
            val previous = """{"role":"assistant","content":"earlier"},"""
            val ordinary = send(client, server.port, previous, history)
            assertEquals(200, ordinary.status.value, "no measured input survives a daemon restart")
            ordinary.bodyAsText()
            val summary = compact(client, server.port, previous, history)
            assertEquals(200, summary.status.value, "a cold compact must reach the provider too")
            summary.bodyAsText()
            val first = send(client, server.port, "", history)
            assertEquals(200, first.status.value, "even a cold first exchange must pass through")
            first.bodyAsText()
            assertEquals(3, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `measured ordinary and compact turns use distinct refusal thresholds`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, stats = stats)
        try {
            server.start()
            val seed = "seed"
            val first = send(client, server.port, "", seed)
            assertEquals(200, first.status.value)
            first.bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, seed, tokens = 250_000)
            val previous = """{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},"""
            val ordinary = send(client, server.port, previous, "new work")
            assertEquals(400, ordinary.status.value, "measured growth exceeds W−R before SSE")
            assertEquals("false", ordinary.headers["x-should-retry"])
            val refusal = ordinary.bodyAsText()
            assertTrue("prompt is too long" in refusal && "measured-text-prefix" in refusal, refusal)
            val compact = compact(client, server.port, previous, "summary")
            assertEquals(200, compact.status.value, "an unmeasured compact reaches the provider")
            assertTrue("event: message_stop" in compact.bodyAsText())
            awaitRows(stats, 3)
            measured(stats, upstream, seed, tokens = 273_000)
            val compactHistory = previous + """{"role":"user","content":"summary"},"""
            val tooLargeCompact = compact(client, server.port, compactHistory, "continue")
            assertEquals(400, tooLargeCompact.status.value, "measured compact input alone exceeds W")
            val compactRefusal = tooLargeCompact.bodyAsText()
            assertRecovery(compactRefusal, inputTokens = 273_000, window = 272_000)
            assertEquals(2, upstream.upstreamBodies.size, "only measured refusals avoid the backend")
            val recorded = recorded(root)
            assertTrue("compaction-preflight-compactable" in recorded, recorded)
            assertTrue("compaction-preflight-compact-overflow" in recorded, recorded)
            assertTrue("compaction-preflight-first-exchange" !in recorded, recorded)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `a compact above its target fits the published serve ceiling`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, window = 400_000, stats = stats, servedWindow = 872_000)
        try {
            server.start()
            compact(client, server.port, "", "seed").bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, "seed", tokens = 643_664)
            val previous = """{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},"""
            val summary = compact(client, server.port, previous, "summary")
            assertEquals(200, summary.status.value, "compaction target is not the backend ceiling")
            summary.bodyAsText()
            awaitRows(stats, 2)
            measured(stats, upstream, "seed", tokens = 900_000)
            val history = previous + """{"role":"user","content":"summary"},"""
            val refused = compact(client, server.port, history, "continue")
            assertEquals(400, refused.status.value)
            assertRecovery(refused.bodyAsText(), inputTokens = 900_000, window = 872_000)
            assertEquals(2, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `a measured compact can use less than empirical generation p99`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, window = 872_000, stats = stats, model = "gpt-6-sol")
        try {
            server.start()
            val seed = send(client, server.port, "", "seed", model = "gpt-6-sol")
            assertEquals(200, seed.status.value)
            seed.bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, "seed", tokens = 865_000, model = "gpt-6-sol")
            val history = """{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},"""
            val compact = send(
                client,
                server.port,
                history,
                "tasked with summarizing conversations",
                model = "gpt-6-sol",
            )
            assertEquals(200, compact.status.value, "p99 output is not a required output minimum")
            compact.bodyAsText()
            assertEquals(2, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `large text growth requests compaction without refusing a fitting compact`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, window = 872_000, stats = stats, model = "gpt-6-sol")
        try {
            server.start()
            val seed = send(client, server.port, "", "seed", model = "gpt-6-sol")
            assertEquals(200, seed.status.value)
            seed.bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, "seed", tokens = 800_000, model = "gpt-6-sol")
            val history = """{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},"""
            val addedText = "x".repeat(70_000)
            val ordinary = send(client, server.port, history, addedText, model = "gpt-6-sol")
            assertEquals(400, ordinary.status.value, "upper text bound should request compaction")
            assertTrue("prompt is too long" in ordinary.bodyAsText())
            val compact = send(
                client,
                server.port,
                history,
                addedText + " tasked with summarizing conversations",
                model = "gpt-6-sol",
            )
            assertEquals(200, compact.status.value, "measured lower bound leaves room for compact")
            assertTrue("event: message_stop" in compact.bodyAsText())
            assertEquals(2, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `a measured first exchange uses the lower bound when text bytes overcount`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, window = 872_000, stats = stats, model = "gpt-6-sol")
        try {
            server.start()
            val seed = send(client, server.port, "", "seed", model = "gpt-6-sol")
            assertEquals(200, seed.status.value)
            seed.bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, "seed", tokens = 800_000, model = "gpt-6-sol")
            val previous = """{"role":"user","content":"seed"},"""
            val response = send(client, server.port, previous, "x".repeat(90_000), model = "gpt-6-sol")
            assertEquals(200, response.status.value, "the first exchange has no compaction to fall back on")
            response.bodyAsText()
            awaitRows(stats, 2)
            measured(stats, upstream, "seed", tokens = 873_000, model = "gpt-6-sol")
            val grown = previous + """{"role":"user","content":"${"x".repeat(90_000)}"},"""
            val oversized = send(client, server.port, grown, "continue", model = "gpt-6-sol")
            assertEquals(400, oversized.status.value, "measured input alone exceeds W")
            val firstRefusal = oversized.bodyAsText()
            assertRecovery(firstRefusal, inputTokens = 873_000, window = 872_000)
            assertEquals(2, upstream.upstreamBodies.size)
            assertTrue("compaction-preflight-first-exchange" in recorded(root))
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `measured text prefix with a large image passes through without a byte refusal`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) {
            engine { requestTimeout = 0 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val stats = PerfStats(root.resolve("perf-preflight.jsonl"))
        val server = head(root, upstream, stats = stats)
        try {
            server.start()
            val baseline = send(client, server.port, "", "seed")
            assertEquals(200, baseline.status.value)
            baseline.bodyAsText()
            awaitRows(stats, 1)
            measured(stats, upstream, "seed", tokens = 240_000)
            val image = "a".repeat(500_000)
            val response = client.post("http://127.0.0.1:${server.port}/v1/messages") {
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", "preflight-session")
                setBody(
                    """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,"system":"test",""" +
                        """"messages":[{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},""" +
                        """{"role":"user","content":[{"type":"image","source":{"type":"base64","media_type":""" +
                        """"image/png","data":"$image"}}]}]}""",
                )
            }
            assertEquals(200, response.status.value, "encoded image bytes are not estimated text tokens")
            response.bodyAsText()
            assertEquals(2, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }
}

/** End-to-end status selection on a shared synthetic upstream and an isolated head per test. */
class HeadServerOverflowStatusTest {
    private val fixture = HeadServerPreflightTest()

    private fun head(root: Path, upstream: MockChatGptUpstream): HeadServer = fixture.head(root, upstream)

    private suspend fun send(
        client: HttpClient,
        port: Int,
        previous: String,
        content: String,
        system: String,
    ): HttpResponse = fixture.send(client, port, previous, content, system)

    private fun recorded(root: Path): String = fixture.recorded(root)

    @Test
    fun `upstream overflow before client content is HTTP 400 before SSE`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val previous = """{"role":"assistant","content":"earlier"},"""
            val overflow = send(client, server.port, previous, "go", "SCENARIO:overflow_sse")
            assertEquals(400, overflow.status.value, "size error precedes HTTP 200 and SSE")
            val body = overflow.bodyAsText()
            assertTrue("invalid_request_error" in body && "prompt is too long" in body, body)
            assertEquals("false", overflow.headers["x-should-retry"])
            val trace = recorded(root)
            assertTrue("\"answer\":{\"status\":400" in trace, trace)
            assertTrue("\"first_frame\"" !in trace, "an undelivered opener cannot mark the first frame: $trace")
            assertEquals(1, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `zero event context rejection before SSE is HTTP 400`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val response = send(client, server.port, "", "go", "SCENARIO:zero_event_overflow")
            assertEquals(400, response.status.value)
            assertEquals("false", response.headers["x-should-retry"])
            assertTrue("prompt is too long" in response.bodyAsText())
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `a healthy turn opens before the bounded silent hold`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) {
            engine { requestTimeout = 20_000 }
            defaultRequest { bearerAuth("test-inference-token") }
        }
        val server = head(root, upstream)
        try {
            server.start()
            val answer = send(client, server.port, "", "go", "SCENARIO:basic")
            assertEquals(200, answer.status.value)
            assertTrue("event: message_stop" in answer.bodyAsText())
            assertEquals(1, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `upstream HTTP context rejection is a downstream HTTP 400`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val response = send(client, server.port, "", "go", "SCENARIO:overflow_http")
            assertEquals(400, response.status.value)
            assertEquals("false", response.headers["x-should-retry"])
            assertTrue("prompt is too long" in response.bodyAsText())
            assertEquals(1, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `overflow after model content stays inside committed SSE`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val response = send(client, server.port, "", "go", "SCENARIO:overflow_after_content")
            assertEquals(200, response.status.value)
            val body = response.bodyAsText()
            assertTrue("partial answer" in body && "event: error" in body, body)
            assertTrue("prompt is too long" in body, body)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }

    @Test
    fun `a different upstream error keeps the in-band SSE contract`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val failure = send(client, server.port, "", "go", "SCENARIO:failed")
            assertEquals(200, failure.status.value)
            assertTrue("event: error" in failure.bodyAsText())
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }
}
