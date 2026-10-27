// PORT-OF: the end-to-end message tests from server/test/codex-proxy.test.mjs @ pre-public-port-baseline — a real
// HeadServer (TestResponsesProvider + mock ChatGPT upstream) exercised over HTTP: SSE wire frames for
// streamed turns, /health + /v1/models shapes, the honest-failure paths, count_tokens NOT
// burning a turn, promote-to-text + mirror on a compact-shaped answer. This is the P3-HEAD gate
// that arms the idle/prefill/refresh scenarios the reader+machine suite deferred.
package splice.head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.CatalogWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.head.compact.ShadowClassifier
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.transport.UpstreamClient
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds

private class FakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-test", "acct-test")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerIntegrationTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) {
        // CIO's engine default caps a call at 15s (CIOEngineConfig.requestTimeout). On a loaded CI
        // runner a turn that lands late trips it, the client hangs up, TurnFinish's terminal write
        // hits a dead socket and the perf row lands as a conn-reset tag instead of "ok" (runs
        // 33608202738 on 2026-09-02 and 33928312116 on 2026-09-04, both at the "expected a perf
        // line" assertion; green locally every time). The perf-row wait (awaitPerfRow) is the one
        // clock a test here should run on, so the engine's own is off.
        engine { requestTimeout = 0 }
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private val port: Int get() = head.port
    private lateinit var head: HeadServer
    private val logs = mutableListOf<String>()
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(
            ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000),
            ModelEntry("gpt-5.4", "5.4", contextWindow = 272_000),
        ),
        windows = CatalogWindows(windowRules = listOf(WindowRule("gpt-5.6", 272_000))),
        defaultContextWindow = 272_000,
    )

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) = runTest {
        tmp = tempDir
        val provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = catalog,
                pinnedModel = "gpt-5.6-sol",
                auth = FakeAuth(),
                locations = ProviderLocations(baseUrl = mock.baseUrl),
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
                loginCommand = "claudex login",
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        )
        head = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
                log = { synchronized(logs) { logs.add(it) } },
            ).copy(
                policy = HeadDeps.HeadPolicy(maxRequestBytes = { 1_024 }),
            ).copy(
                // The shadow classifier's own log rides the SAME sink the assertions read, so it
                // cannot come from the fixture's silent default.
                stores = headStores(tmp).copy(shadow = ShadowClassifier(log = { synchronized(logs) { logs.add(it) } })),
            ),
        )
        head.start()
        awaitListening(port)
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
    fun `inference routes reject missing and incorrect local credentials`() = runTest {
        val unauthenticated = HttpClient(CIO)
        try {
            val missing = unauthenticated.post("http://127.0.0.1:$port/v1/messages") {
                header("Content-Type", "application/json")
                setBody("""{"model":"claude-codex--gpt-5.6-sol","messages":[]}""")
            }
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertTrue(missing.bodyAsText().contains("authentication_error"))
            val wrong = unauthenticated.get("http://127.0.0.1:$port/v1/models") {
                header("Authorization", "Bearer definitely-wrong")
            }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        } finally {
            unauthenticated.close()
        }
    }

    @Test
    fun `oversized request is rejected before parsing or upstream work`() = runTest {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody("x".repeat(2_048))
        }
        assertEquals(413, response.status.value)
        val said = "[splice] This request is 2 KB, more than the 1 KB splice accepts."
        assertTrue(response.bodyAsText().contains(said))
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

    // DR-168: nothing asserted the bytes splice actually puts on the upstream wire. codex-splice's
    // fourth finding, corrected by its own verifier — TurnDrive.bodyJson (assembled in
    // TurnDriveFactory) was dead: the round loop posts drive.requestBody.toString() (RoundStrategy),
    // so a row pinning that field would have pinned nothing; it is deleted alongside this arm. The
    // real gap sat one level down: with SseRoundDriver's live dispatch body mutated to stream:false,
    // every dialect contract suite, this whole suite and the :upstream transport suites stayed
    // GREEN. This arm reads the request the mock DECODED off the socket
    // (MockChatGptUpstream.upstreamBodies — zstd-inflated, exactly what the ChatGPT backend would
    // parse) and compares the WHOLE body to the canonical bytes of a basic TestResponsesProvider turn.
    // Pinned as literal bytes on purpose: the closed ResponsesRequest DTO makes field order =
    // declaration order, so a reordered field, a dropped `stream:true`, or a Chat-only knob leaking
    // in all go RED here. Update the pin only after reading the diff.
    @Test
    fun `upstream wire body is the canonical Responses request, byte for byte`() = runTest {
        val before = mock.upstreamBodies.size
        messages("basic")
        val captured = mock.upstreamBodies.drop(before)
        assertEquals(1, captured.size, "one basic turn is exactly one upstream POST, got: $captured")
        val (scenario, body) = captured.single()
        assertEquals("basic", scenario)
        assertTrue(body.contains("\"stream\":true"), "the upstream body must ask for a stream: $body")
        // One wire field per line for the reader; joined back to the single line the socket carried
        // before comparing. prompt_cache_key/thread_id are "splice-" + sha256(user content)[:32] — a
        // pure function of the request, so the pin holds across processes; derived here (not pasted)
        // because the 32-hex literal trips the repo's secret scan by entropy alone.
        val cacheKey = "splice-" + MessageDigest.getInstance("SHA-256").digest("go".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(CACHE_KEY_HEX)
        val expected = """
            |{"model":"gpt-5.6-sol",
            |"input":[{"role":"user","content":"go"}],
            |"store":false,
            |"stream":true,
            |"include":["reasoning.encrypted_content"],
            |"prompt_cache_key":"$cacheKey",
            |"instructions":"You are a test. SCENARIO:basic",
            |"reasoning":{"effort":"high","summary":"detailed"},
            |"stream_options":{"reasoning_summary_delivery":"sequential_cutoff"}}
        """.trimMargin().lines().joinToString("")
        assertEquals(expected, body)
    }

    @Test
    fun `malformed SSE frame is skipped with FRAMES_SKIPPED telemetry and one snippet log`() = runTest {
        val before = logs.size
        val jsonl = tmp.resolve("perf.jsonl")
        val beforeRows = perfRows(jsonl).size
        val sse = messages("malformed_sse")
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
        val row = awaitPerfRow(jsonl, beforeRows) { it.contains("\"outcome\":\"ok\"") }
        val scoped = synchronized(logs) { logs.drop(before) }
        val malformedLines = scoped.filter { it.contains("malformed SSE frame skipped: {not-json}") }
        assertEquals(1, malformedLines.size, "expected exactly one malformed-frame log line, got: $scoped")
        assertTrue(row != null, "expected this turn's perf row, got: ${perfRows(jsonl)}")
        assertTrue(row!!.contains("\"frames_skipped\":1"), "expected frames_skipped 1 in: $row")
    }

    @Test
    fun `turn records a perf JSONL row with pipeline marks`() = runTest {
        val jsonl = tmp.resolve("perf.jsonl")
        val beforeRows = perfRows(jsonl).size
        messages("basic")
        val row = awaitPerfRow(jsonl, beforeRows) { it.contains("\"outcome\":\"ok\"") }
        assertTrue(row != null, "expected a new perf JSONL row after this turn: ${perfRows(jsonl)}")
        val expectedFields = listOf(
            "recv", "parse", "build", "gate", "headers", "first_byte",
            "first_frame", "first_delta", "stream_end", "finish", "total",
        )
        for (field in expectedFields) {
            assertTrue(row!!.contains("\"$field\":"), "perf row missing $field: $row")
        }
    }

    @Test
    fun `toolcall streams tool_use with input_json_delta and stop_reason tool_use`() = runTest {
        val sse = messages("toolcall")
        assertTrue(sse.contains("\"type\":\"tool_use\""))
        assertTrue(sse.contains("input_json_delta"))
        assertTrue(sse.contains("\"stop_reason\":\"tool_use\""))
    }

    @Test
    fun `multipart reasoning stays native and is never mirrored into transcript text`() = runTest {
        val sse = messages("multipart")
        assertTrue(sse.contains("thinking_delta"))
        assertFalse(sse.contains("[reasoning summary]"), sse)
        assertTrue(sse.contains("Answer text."))
    }

    @Test
    fun `oversized upstream SSE frame emits an honest provider error`() = runTest {
        val sse = messages("oversized_sse")
        assertTrue(sse.contains("event: error"))
        // The head's line for an oversized event (failure-handling slice 2); the limit and size stay in the log.
        assertTrue(sse.contains("[splice] claudex sent a stream event larger than splice accepts."), sse)
        assertFalse(sse.contains("event: message_stop"))
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

    // Failure-handling slice 3: a body with no events is read by the head's own reader only when it is the provider's
    // JSON error. Any other body, this HTML page included, is the path breaking before anything came,
    // Torn(NO_EVENTS) (final-design.md section 7, item 4), so its words no longer pick a login cause as G2's did.
    @Test
    fun `a zero-event page that is no JSON error is a broken path the client sends again`() = runTest {
        val sse = messages("zero_event_auth")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("overloaded_error"), sse)
        assertFalse(sse.contains("authentication_error"), sse)
    }

    @Test
    fun `AUTHENTICATION via UpstreamFailed appends the per-head login hint`() = runTest {
        val before = head.healthSnapshot()
        val sse = messages("authfail")
        assertTrue(sse.contains("event: error"))
        assertTrue(sse.contains("authentication_error"))
        assertTrue(sse.contains(LOGIN_HINT), sse)
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

    // PR #115 end to end: the backend's capacity signal in every wire shape it takes, driven through
    // the real head. UpstreamFailureRetryWordingTest proves the VERDICT; these prove the head RETRIES
    // the pre-stream 503 (the mock counts POSTs), that a signal which clears is invisible to the
    // client, and that what the client finally sees is overloaded_error — the type Claude Code
    // itself retries on — never the terminal api_error that ended the 2026-09-01 20:56 compaction.
    @Test
    fun `a 503 capacity signal is retried by the head and surfaces as overloaded_error when it persists`() = runTest {
        val sse = messages("overload_503")
        assertTrue(sse.contains("event: error"), sse)
        assertTrue(sse.contains("overloaded_error"), sse)
        assertFalse(sse.contains("api_error"), sse)
        val posts = mock.upstreamBodies.count { it.first == "overload_503" }
        assertTrue(posts >= 2, "the head gave up on the capacity signal without retrying: $posts POST(s)")
    }

    @Test
    fun `a capacity signal that clears on the head's retry completes the turn`() = runTest {
        val sse = messages("overload_once")
        assertTrue(sse.contains("ok after auth"), sse)
        assertTrue(sse.contains("event: message_stop"), sse)
        assertEquals(2, mock.upstreamBodies.count { it.first == "overload_once" })
    }

    @Test
    fun `an in-stream capacity signal surfaces as overloaded_error, never api_error`() = runTest {
        val sse = messages("overload_sse")
        assertTrue(sse.contains("event: error"), sse)
        assertTrue(sse.contains("overloaded_error"), sse)
        assertFalse(sse.contains("api_error"), sse)
    }

    @Test
    fun `a 4xx wearing the overload code keeps its invalid_request verdict and is sent once`() = runTest {
        // THE VERDICT IS THE HALF THAT SURVIVES: a 403 whose words spell an overload is still no
        // overloaded_error. Failure-handling slice 3 changed the count. V4-62 re-sent every status because the
        // shared classifier was not reliable enough to refuse one; each API type's reader now reads a 403 as
        // Forbidden, which the provider answers the same way for the same request, so it is sent once
        // (final-design.md section 8).
        val sse = messages("overload_403")
        assertTrue(sse.contains("event: error"), sse)
        assertTrue(sse.contains("invalid_request_error"), sse)
        assertFalse(sse.contains("overloaded_error"), sse)
        assertEquals(
            1,
            mock.upstreamBodies.count { it.first == "overload_403" },
            "a 403 is forbidden as sent, so the same request is not sent again",
        )
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
    fun `count_tokens rejects malformed JSON and does not undercount unicode`() = runTest {
        val malformed = client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val unicode = "世界🙂".repeat(20)
        val response = client.post("http://127.0.0.1:$port/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","messages":[{"role":"user","content":"$unicode"}]}""",
            )
        }
        val estimate = Regex("\"input_tokens\":(\\d+)")
            .find(response.bodyAsText())
            ?.groupValues
            ?.get(1)
            ?.toLong()
        assertTrue(estimate != null && estimate >= 60, "Unicode estimate must be conservative: $estimate")
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
        assertTrue(body.contains("[splice] claudex does not serve claude-3-opus."), body)
    }

    @Test
    fun `non-streaming requests get one collected Anthropic Messages JSON body`() = runTest {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":false,"max_tokens":8000,
                    "system":"You are a test. SCENARIO:basic",
                    "messages":[{"role":"user","content":"go"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        // a single JSON message envelope, NOT an SSE frame stream
        assertFalse(body.contains("event:"))
        assertFalse(body.contains("data:"))
        assertTrue(body.contains("\"type\":\"message\""))
        assertTrue(body.contains("\"role\":\"assistant\""))
        assertTrue(body.contains("ok after auth")) // the model text collected from the SSE
        assertTrue(body.contains("\"stop_reason\":\"end_turn\""))
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
        assertTrue(body.contains("[splice] splice could not parse this request."), body)
    }
}

// The 32 hex chars the gateway keeps of the sha256 for prompt_cache_key / thread_id.
private const val CACHE_KEY_HEX = 32

// The head's per-head login hint, as its line for a login the provider refused says it (failure-handling slice 2).
private const val LOGIN_HINT = "Run claudex login, then send the message again."
