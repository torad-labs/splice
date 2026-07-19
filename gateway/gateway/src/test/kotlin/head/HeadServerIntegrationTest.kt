// PORT-OF: the end-to-end message tests from server/test/codex-proxy.test.mjs @ 4ca99f7 — a real
// HeadServer (CodexProvider + mock ChatGPT upstream) exercised over HTTP: SSE wire frames for
// streamed turns, /health + /v1/models shapes, the honest-failure paths, count_tokens NOT
// burning a turn, promote-to-text + mirror on a compact-shaped answer. This is the P3-HEAD gate
// that arms the idle/prefill/refresh scenarios the reader+machine suite deferred.
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import mock.MockChatGptUpstream
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
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
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerIntegrationTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private val port = 39240
    private lateinit var head: HeadServer
    private val logs = mutableListOf<String>()
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(
            ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000),
            ModelEntry("gpt-5.4", "5.4", contextWindow = 272_000),
        ),
        windowRules = listOf(WindowRule("gpt-5.6", 272_000)),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp() = runTest {
        tmp = Files.createTempDirectory("head-it")
        val provider = CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = FakeAuth(),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
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
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = { logs.add(it) }),
                compactStats = CompactStats(tmp.resolve("compact.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage.json"), tmp.resolve("ratelimit.json")),
                perfStats = PerfStats(tmp.resolve("perf.jsonl")),
                log = { logs.add(it) },
            ),
        )
        head.start()
        Thread.sleep(700) // Netty warmup
    }

    @AfterAll
    fun tearDown() = runTest {
        head.stop()
        client.close()
        mock.stop()
    }

    private suspend fun messages(scenario: String, model: String = "claude-codex--gpt-5.6-sol"): String =
        client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"$model","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:$scenario",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()

    @Test
    fun `health carries version and port`() = runTest {
        val body = client.get("http://127.0.0.1:$port/health").bodyAsText()
        assertTrue(body.contains("\"version\""))
        assertTrue(body.contains("\"port\":$port"))
        assertTrue(body.contains("\"ok\":true"))
    }

    @Test
    fun `server tcp_nodelay is verified and logged on the accepted connection`() = runTest {
        // G26: reuses the already-started head/port/client/logs from @BeforeAll (HeadServerLoadTest's
        // ephemeral-port fix, commit 449772e, documents why a second server is not spun up here).
        client.get("http://127.0.0.1:$port/health")
        assertTrue(logs.any { it.contains("tcp_nodelay(server)=") })
    }

    @Test
    fun `models are discovery-wrapped and include the pinned model`() = runTest {
        val body = client.get("http://127.0.0.1:$port/v1/models").bodyAsText()
        assertTrue(body.contains("claude-codex--gpt-5.4"))
        // the pinned model IS discovered too — else it's missing from the /model picker
        assertTrue(body.contains("claude-codex--gpt-5.6-sol"))
    }

    @Test
    fun `basic turn streams a clean Anthropic SSE sequence`() = runTest {
        val sse = messages("basic")
        assertTrue(sse.contains("event: message_start"))
        assertTrue(sse.contains("event: ping"))
        assertTrue(sse.contains("event: content_block_start"))
        assertTrue(sse.contains("text_delta"))
        assertTrue(sse.contains("ok after auth"))
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
        assertTrue(sse.trimEnd().endsWith("event: message_stop\ndata: {\"type\":\"message_stop\"}"))
    }

    @Test
    fun `malformed SSE frame is skipped with FRAMES_SKIPPED telemetry and one snippet log`() = runTest {
        val before = logs.size
        val sse = messages("malformed_sse")
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
        val scoped = logs.drop(before)
        val malformedLines = scoped.filter { it.contains("malformed SSE frame skipped: {not-json}") }
        assertEquals(1, malformedLines.size, "expected exactly one malformed-frame log line, got: $scoped")
        val perfLine = scoped.lastOrNull { it.contains("] perf outcome=ok") }
        assertTrue(perfLine != null, "expected a perf line in the log, got: $scoped")
        assertTrue(perfLine!!.contains("frames_skipped=1"), "expected frames_skipped=1 in: $perfLine")
    }

    @Test
    fun `turn records perf telemetry - log line and JSONL row with pipeline marks`() = runTest {
        messages("basic")
        val perfLine = logs.lastOrNull { it.contains("] perf outcome=ok") }
        assertTrue(perfLine != null, "expected a perf line in the log, got: $logs")
        val expectedFields = listOf(
            "recv=", "parse=", "build=", "gate=", "headers=", "first_byte=",
            "first_frame=", "first_delta=", "stream_end=", "finish=", "total=",
        )
        for (field in expectedFields) {
            assertTrue(perfLine!!.contains(field), "perf line missing $field: $perfLine")
        }
        val rows = Files.readString(tmp.resolve("perf.jsonl")).trim().lines()
        assertTrue(rows.isNotEmpty(), "expected at least one perf JSONL row")
        val last = rows.last()
        assertTrue(last.contains("\"outcome\":\"ok\"") && last.contains("\"total\":"), "bad row: $last")
    }

    @Test
    fun `toolcall streams tool_use with input_json_delta and stop_reason tool_use`() = runTest {
        val sse = messages("toolcall")
        assertTrue(sse.contains("\"type\":\"tool_use\""))
        assertTrue(sse.contains("input_json_delta"))
        assertTrue(sse.contains("\"stop_reason\":\"tool_use\""))
    }

    @Test
    fun `multipart reasoning mirrors into a visible text block`() = runTest {
        val sse = messages("multipart")
        assertTrue(sse.contains("thinking_delta"))
        assertTrue(sse.contains("[reasoning summary]")) // the L2 mirror
        assertTrue(sse.contains("Answer text."))
    }

    @Test
    fun `overflow via SSE failure emits an error event with prompt-is-too-long`() = runTest {
        val sse = messages("overflow_sse")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("prompt is too long"))
        assertFalse(sse.contains("event: message_stop")) // never a clean stop after failure
    }

    @Test
    fun `truncated stream emits an honest overloaded error`() = runTest {
        val sse = messages("truncated")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("overloaded_error"))
    }

    @Test
    fun `local-origin failure increments localOriginErrors, not providerErrors`() = runTest {
        val before = head.healthSnapshot()
        messages("truncated") // stream ended without a terminal event -> ErrorType.OVERLOADED -> LOCAL
        val after = head.healthSnapshot()
        assertEquals(before.localOriginErrors + 1, after.localOriginErrors)
        assertEquals(before.providerErrors, after.providerErrors)
    }

    @Test
    fun `provider-error failure increments providerErrors, not localOriginErrors`() = runTest {
        val before = head.healthSnapshot()
        messages("overflow_sse") // invalid_request_error from upstream -> ErrorType.INVALID_REQUEST -> PROVIDER
        val after = head.healthSnapshot()
        assertEquals(before.providerErrors + 1, after.providerErrors)
        assertEquals(before.localOriginErrors, after.localOriginErrors)
    }

    @Test
    fun `zero-event auth-shaped body classifies as authentication with a login hint`() = runTest {
        val sse = messages("zero_event_auth")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("authentication_error"))
        assertTrue(sse.contains("run: claudex login"))
        assertFalse(sse.contains("overloaded_error"))
    }

    @Test
    fun `AUTHENTICATION via UpstreamFailed appends the per-head login hint`() = runTest {
        val before = head.healthSnapshot()
        val sse = messages("authfail")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("authentication_error"))
        assertTrue(sse.contains("run: claudex login"))
        // UpstreamFailed's status/body are the literal HTTP response the retry loop gave up on
        // after exhausting retries -- the upstream host responded, so this is PROVIDER, not LOCAL.
        val after = head.healthSnapshot()
        assertEquals(before.providerErrors + 1, after.providerErrors)
        assertEquals(before.localOriginErrors, after.localOriginErrors)
    }

    @Test
    fun `zero-event empty body still falls back to the honest overloaded error`() = runTest {
        val sse = messages("zero_event_empty")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("overloaded_error"))
    }

    @Test
    fun `compactish turn promotes reasoning to text (mirror + promote)`() = runTest {
        // a compact-shaped SCENARIO won't trigger classifyCompact (no marker), so this proves
        // promote-to-text on a reasoning-only turn with an empty text channel.
        val sse = messages("compactish")
        assertTrue(sse.contains("Goal: port the proxy"))
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
    }

    @Test
    fun `count_tokens returns a local estimate without an upstream turn`() = runTest {
        val before = mock.upstreamBodies.size
        val body = client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody("""{"model":"claude-codex--gpt-5.6-sol","messages":[{"role":"user","content":"hello there"}]}""")
        }.bodyAsText()
        assertTrue(body.contains("\"input_tokens\""))
        assertEquals(before, mock.upstreamBodies.size) // NO upstream turn was made
    }

    @Test
    fun `claude model ids are rejected with a 400-shaped error`() = runTest {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-3-opus","stream":true,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:basic",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"))
        assertTrue(body.contains("proxies its own models only"))
    }

    @Test
    fun `non-streaming requests are pre-stream rejected with a 400-shaped error`() = runTest {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":false,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:basic",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"))
        assertTrue(body.contains("serves streaming clients only"))
    }

    @Test
    fun `malformed request body is pre-stream rejected with a 400-shaped error`() = runTest {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("invalid_request_error"))
        assertTrue(body.contains("invalid request body"))
    }
}
