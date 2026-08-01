// NEW (review of #72, WsRoundDriver.kt:85): the pre-content fallback decision is ONE boolean, and
// flipping it changes the user-visible failure mode — a WS round answered with a failure terminal
// would be served raw over the WebSocket, bypassing UpstreamClient's retry, its single-flight 401
// refresh and the shared 429 cooldown. Nothing tested it. These are HTTP-level, through a real
// HeadServer, because "the SSE path is reached" is only observable at the upstream.
//
// The two cases are opposites and both matter:
//   failure BEFORE any client frame -> abandon the WS round, SSE serves the turn (retry intact)
//   failure AFTER a frame           -> stay on the WS path; re-serving would duplicate output
package head

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
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
import splice.spi.Provider
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import splice.spi.WsRoundRunner
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private class WsFakeAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok-ws", "acct-ws")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private fun ev(json: String): JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(json) as JsonObject

/** A runner that replays a scripted round, so the driver's decision is the only variable. */
private class ScriptedRunner(private val events: List<String>) : WsRoundRunner {
    var attempts = 0
    var bypassed = 0
    var endedOk = 0

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): Flow<JsonObject>? {
        attempts += 1
        return flowOf(*events.map(::ev).toTypedArray())
    }

    override fun isFailureTerminal(event: JsonObject): Boolean =
        event["type"].toString().trim('"') in setOf("response.failed", "response.error", "error")

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (ok) endedOk += 1
    }

    override fun roundBypassed(meta: TurnMeta) {
        bypassed += 1
    }
}

/** A real codex provider with ONE member swapped. Interface delegation, not subclassing:
 *  CodexProvider is final and wsRunner is a final override, and delegating keeps every other
 *  behaviour (buildTurn, the stream translator, the SSE path) genuinely real. */
private class ScriptedWsProvider(
    private val inner: CodexProvider,
    private val runner: ScriptedRunner,
) : Provider by inner {
    override val wsRunner: WsRoundRunner get() = runner
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WsRoundDriverTest {

    private val mock = MockChatGptUpstream()
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private lateinit var tmp: Path

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("ws-driver")
    }

    @AfterAll
    fun tearDown() {
        client.close()
        mock.stop()
    }

    private fun freshPort(): Int = ServerSocket(0).use { it.localPort }

    private fun head(port: Int, runner: ScriptedRunner): HeadServer {
        val provider = ScriptedWsProvider(
            CodexProvider(
                tuning = ProviderTuning(
                    key = "codex",
                    label = "claudex",
                    catalog = ModelCatalog(
                        discoveryPrefix = "claude-codex--",
                        models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                        defaultContextWindow = 272_000,
                    ),
                    pinnedModel = "gpt-5.6-sol",
                    auth = WsFakeAuth(),
                    baseUrl = mock.baseUrl,
                    watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
                    loginCommand = "claudex login",
                ),
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = false,
                configEffort = "high",
                configSummary = "detailed",
            ),
            runner,
        )
        return HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 2),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact-$port.jsonl")),
                usageStore = UsageStore(tmp.resolve("usage-$port.json"), tmp.resolve("rl-$port.json")),
                perfStats = PerfStats(tmp.resolve("perf-$port.jsonl")),
                log = {},
                requestMaterializationGate = RequestMaterializationGate(2),
            ),
        )
    }

    private fun turn(port: Int): String = runBlocking {
        client.post("http://127.0.0.1:$port/v1/messages") {
            setBody(
                """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
    }

    /** FAILURE BEFORE ANY CLIENT FRAME -> the round is abandoned and SSE serves the turn, so the
     *  upstream POST happens and the client sees the normal answer. Without this the failure is
     *  delivered raw over the WebSocket, skipping retry / 401 refresh / 429 cooldown entirely. */
    @Test
    fun `a failure terminal before any client frame falls back to the SSE path`() {
        val runner = ScriptedRunner(listOf("""{"type":"response.failed","response":{"id":"r1"}}"""))
        val port = freshPort()
        val h = head(port, runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(port)
            assertEquals(1, runner.attempts, "the overlay was tried")
            assertEquals(1, runner.bypassed, "and it reported the bypass so the chain is cleared")
            assertEquals(0, runner.endedOk, "a failure terminal is NOT a clean round")
            assertTrue(mock.upstreamBodies.size > before, "the SSE upstream must have served the turn")
            assertTrue(sse.contains("event: message_stop"), "the client sees a normal completed turn")
        } finally {
            runBlocking { h.stop() }
        }
    }

    /** FAILURE AFTER A FRAME -> the client has already seen output, so re-serving over SSE would
     *  duplicate it. The round stays on the WS path and no upstream POST is made. */
    @Test
    fun `a failure terminal AFTER a client frame stays on the websocket path`() {
        val runner = ScriptedRunner(
            listOf(
                """{"type":"response.created","response":{"id":"r1"}}""",
                """{"type":"response.output_item.added","output_index":0,""" +
                    """"item":{"type":"message","role":"assistant"}}""",
                """{"type":"response.content_part.added","output_index":0,"content_index":0,""" +
                    """"part":{"type":"output_text","text":""}}""",
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"hello"}""",
                """{"type":"response.failed","response":{"id":"r1"}}""",
            ),
        )
        val port = freshPort()
        val h = head(port, runner)
        runBlocking { h.start() }
        try {
            val before = mock.upstreamBodies.size
            val sse = turn(port)
            // Rounds > 1 are the head's own re-anchor retries, which a post-content failure gets on
            // EITHER transport — pre-existing behaviour and not what this test is about.
            assertTrue(runner.attempts >= 1, "the overlay served the round")
            assertEquals(
                0,
                runner.bypassed,
                "content was already emitted, so the pre-content fallback must NOT fire — re-serving " +
                    "over SSE would duplicate output the client already has",
            )
            assertEquals(before, mock.upstreamBodies.size, "no SSE upstream request may be made")
            assertTrue(sse.contains("hello"), "the content the client already saw is preserved")
            assertFalse(sse.isEmpty())
        } finally {
            runBlocking { h.stop() }
        }
    }
}
