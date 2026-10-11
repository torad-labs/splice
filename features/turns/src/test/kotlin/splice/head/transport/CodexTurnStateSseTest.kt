// NEW: the real SSE fallback captures an HTTP-only routing token for one turn's continuation.
package splice.head.transport

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.stream.FoldConfig
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class CodexTurnStateSseTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `HTTP-only turn state is echoed on the continuation but not on another product turn`() = runTest {
        assertEquals(listOf(null, "sse-only-state", null), probe(httpState = "sse-only-state", turns = 2).headers)
    }

    @Test
    fun `SSE event-only state is not echoed on the continuation`() = runTest {
        assertEquals(listOf(null, null), probe(eventState = "event-only-state").headers)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["private\nstate", "private\rstate", "private\u0000state", "private\u007fstate", "privateéstate"],
    )
    fun `invalid WebSocket state does not poison SSE fallback retries or continuations`(state: String) = runTest {
        val result = probe(wsState = state, retry = true)
        assertEquals(listOf(null, null, null), result.headers)
        assertEquals(mapOf("x-codex-turn-state" to state), result.runner?.meta?.upstreamHeaders?.snapshot())
        val omissions = result.logs.filter { it.contains("omitting invalid upstream turn-state HTTP header") }
        assertEquals(1, omissions.size, "one omission notice across retries and continuation rounds")
        assertTrue(result.logs.none { it.contains("private") }, "no state value in diagnostics")
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "state\tpart"])
    fun `valid empty and tab header states still echo without an omission notice`(state: String) = runTest {
        val result = probe(wsState = state)
        // The loopback HTTP server normalizes internal TAB to SP; it must not be omitted.
        val received = state.replace('\t', ' ')
        assertEquals(listOf(received, received), result.headers)
        assertEquals(mapOf("x-codex-turn-state" to state), result.runner?.meta?.upstreamHeaders?.snapshot())
        assertTrue(result.logs.none { it.contains("omitting invalid upstream turn-state HTTP header") })
    }

    private data class ProbeResult(
        val headers: List<String?>,
        val logs: List<String>,
        val runner: CapturedStateFallback?,
    )

    private suspend fun probe(
        httpState: String? = null,
        eventState: String? = null,
        wsState: String? = null,
        retry: Boolean = false,
        turns: Int = 1,
    ): ProbeResult {
        val headers = CopyOnWriteArrayList<String?>()
        val logs = CopyOnWriteArrayList<String>()
        val runner = wsState?.let(::CapturedStateFallback)
        val server = upstreamServer(headers, httpState, eventState, retry)
        val head = HeadServer(
            provider = withFallback(provider(server), runner),
            listenPort = 0,
            deps = headDeps(
                tempDir,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
                log = { logs += it },
            ),
        )
        val client = HttpClient(CIO)
        try {
            head.start()
            repeat(turns) {
                val answer = client.post("http://127.0.0.1:${head.port}/v1/messages") {
                    bearerAuth("test-inference-token")
                    header("Content-Type", "application/json")
                    setBody(
                        """{"model":"claude-codex--gpt-5.6-luna","stream":true,
                            "messages":[{"role":"user","content":"synthetic routing probe"}]}""",
                    )
                }.bodyAsText()
                assertTrue(answer.contains("FINAL ANSWER"), answer)
            }
            return ProbeResult(headers.toList(), logs.toList(), runner)
        } finally {
            head.stop()
            client.close()
            server.stop(0)
        }
    }

    private fun upstreamServer(
        headers: MutableList<String?>,
        httpState: String?,
        eventState: String?,
        retry: Boolean,
    ): HttpServer {
        val rounds = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            headers += exchange.requestHeaders.getFirst("x-codex-turn-state")
            val round = rounds.getAndIncrement()
            val first = round == 0
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            if (first && httpState != null) exchange.responseHeaders.set("x-codex-turn-state", httpState)
            exchange.sendResponseHeaders(if (retry && round == 1) 503 else 200, 0)
            exchange.responseBody.use { body ->
                val metadata = if (first && eventState != null) {
                    "data: {\"type\":\"response.metadata\",\"headers\":{\"x-codex-turn-state\":\"$eventState\"}}\n\n"
                } else {
                    ""
                }
                body.write((metadata + events(first)).toByteArray(Charsets.UTF_8))
            }
        }
        server.start()
        return server
    }

    private fun withFallback(codex: CodexProvider, runner: CapturedStateFallback?): Provider =
        if (runner == null) {
            codex
        } else {
            object : Provider by codex {
                override val wsRunner: WsRoundRunner = runner
            }
        }

    /** Captures at the WS boundary, then fails before content so the real head drives SSE. */
    private class CapturedStateFallback(private val state: String) : WsRoundRunner {
        var meta: TurnMeta? = null

        override suspend fun attempt(
            bodyJson: String,
            meta: TurnMeta,
            turnHeaders: Map<String, String>,
            creds: Credentials,
        ): WsRound? {
            if (this.meta != null) return null
            this.meta = meta
            return WsRound(
                flow {
                    meta.upstreamHeaders.capture("x-codex-turn-state", state)
                    val failure = """{"type":"response.failed","response":{"status":"failed"}}"""
                    emit(Json.parseToJsonElement(failure) as JsonObject)
                },
                WsRoundAbort {},
            )
        }

        override fun isFailureTerminal(event: JsonObject): Boolean = true
        override fun roundEnded(meta: TurnMeta, ok: Boolean) = Unit
        override fun roundBypassed(meta: TurnMeta) = Unit
    }

    private fun provider(server: HttpServer): CodexProvider {
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("test-token", "test-account")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
        }
        return CodexProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "codex", label = "claudex"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-luna", "Luna", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-luna",
                auth = auth,
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${server.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
            quirks = CodexQuirks().defaultQuirks().withSummaryDelivery(null),
            foldConfig = FoldConfig(models = setOf("gpt-5.6-luna")),
        )
    }

    private fun events(first: Boolean): String {
        val prefix = if (first) {
            listOf(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs-test"}}""",
                """{"type":"response.reasoning_summary_text.delta","output_index":0,""" +
                    """"delta":"Synthetic reasoning for the continuation."}""",
                """{"type":"response.output_item.done","output_index":0,""" +
                    """"item":{"type":"reasoning","id":"rs-test","encrypted_content":"test-cipher"}}""",
            )
        } else {
            emptyList()
        }
        val text = if (first) "TENTATIVE ANSWER" else "FINAL ANSWER"
        val reasoning = if (first) 516 else 800
        val messages = listOf(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"message"}}""",
            """{"type":"response.output_text.delta","output_index":1,"delta":"$text"}""",
            """{"type":"response.output_item.done","output_index":1}""",
            """{"type":"response.completed","response":{"id":"test-response","status":"completed","output":[],
                "usage":{"input_tokens":100,"output_tokens":800,""" +
                """"output_tokens_details":{"reasoning_tokens":$reasoning}}}}""",
        )
        return (prefix + messages).joinToString("") { "data: ${Json.parseToJsonElement(it)}\n\n" }
    }
}
