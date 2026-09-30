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
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.InputDigest
import splice.core.perf.PerfKeys
import splice.core.perf.TurnPerf
import splice.core.storage.ActivityDays
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
    private fun head(
        root: Path,
        upstream: MockChatGptUpstream,
        window: Long = 272_000,
        stats: PerfStats = PerfStats(root.resolve("perf-preflight.jsonl")),
        model: String = "gpt-5.6-sol",
    ): HeadServer {
        val catalog = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(ModelEntry(model, contextWindow = window)),
            defaultContextWindow = window,
            pinnedModel = model,
            compactionReserveDefaults = CodexCompactionReserves,
        )
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

    private suspend fun send(
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

    private fun recorded(root: Path): String {
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
            val measuredBody = Json.parseToJsonElement(upstream.upstreamBodies.last().second).jsonObject
            measured(stats, upstream, seed, tokens = 250_000)
            val anchored = stats.measuredInputs.estimate(
                "preflight-session",
                "splice-" + InputDigest.hex(seed).take(32),
                "gpt-5.6-sol",
                measuredBody,
            )
            assertEquals(250_000L, anchored?.lowerTokens, "synthetic measurement must be in the head's live stats")
            val previous = """{"role":"user","content":"seed"},{"role":"assistant","content":"earlier"},"""
            val ordinary = send(client, server.port, previous, "new work")
            assertEquals(400, ordinary.status.value, "measured growth exceeds W−R before SSE")
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
            tooLargeCompact.bodyAsText()
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
            oversized.bodyAsText()
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

    @Test
    fun `genuine upstream overflow remains an SSE error inside HTTP 200`(@TempDir root: Path) = runTest {
        val upstream = MockChatGptUpstream()
        val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
        val server = head(root, upstream)
        try {
            server.start()
            val overflow = send(client, server.port, "", "go", "SCENARIO:overflow_sse")
            assertEquals(200, overflow.status.value)
            val sse = overflow.bodyAsText()
            assertTrue("event: error" in sse && "prompt is too long" in sse, sse)
            assertEquals(1, upstream.upstreamBodies.size)
        } finally {
            server.stop()
            client.close()
            upstream.stop()
        }
    }
}
