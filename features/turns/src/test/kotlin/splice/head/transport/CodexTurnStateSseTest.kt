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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.stream.FoldConfig
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodexProvider
import splice.provider.codex.CodexQuirks
import splice.upstream.ProviderTuning
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
        val headers = CopyOnWriteArrayList<String?>()
        val rounds = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/responses") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            headers += exchange.requestHeaders.getFirst("x-codex-turn-state")
            val first = rounds.getAndIncrement() == 0
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            if (first) exchange.responseHeaders.set("x-codex-turn-state", "sse-only-state")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write(events(first).toByteArray(Charsets.UTF_8))
            }
        }
        server.start()
        val head = HeadServer(
            provider = provider(server),
            listenPort = 0,
            deps = headDeps(tempDir, upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2)),
        )
        val client = HttpClient(CIO)
        try {
            head.start()
            repeat(2) {
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
            assertEquals(listOf(null, "sse-only-state", null), headers.toList())
        } finally {
            head.stop()
            client.close()
            server.stop(0)
        }
    }

    private fun provider(server: HttpServer): CodexProvider {
        val auth = object : RefreshableAuthProvider {
            override suspend fun credentials(): Credentials = Credentials.Bearer("test-token", "test-account")
            override suspend fun refresh(): Credentials = credentials()
            override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
        }
        return CodexProvider(
            tuning = ProviderTuning(
                key = "codex",
                label = "claudex",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-codex--",
                    models = listOf(ModelEntry("gpt-5.6-luna", "Luna", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-luna",
                auth = auth,
                baseUrl = "http://127.0.0.1:${server.address.port}",
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = false,
            configEffort = "high",
            configSummary = "detailed",
            quirks = CodexQuirks().defaultQuirks().withSummaryDelivery(null),
            foldConfig = FoldConfig(models = setOf("gpt-5.6-luna")),
        )
    }

    private fun events(first: Boolean): String {
        val prefix = if (first) {
            listOf(
                """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs-test"}}""",
                """{"type":"response.reasoning_summary_text.delta","output_index":0,"delta":"Synthetic reasoning for the continuation."}""",
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs-test","encrypted_content":"test-cipher"}}""",
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
                "usage":{"input_tokens":100,"output_tokens":800,"output_tokens_details":{"reasoning_tokens":$reasoning}}}}""",
        )
        return (prefix + messages).joinToString("") { "data: ${Json.parseToJsonElement(it)}\n\n" }
    }
}
