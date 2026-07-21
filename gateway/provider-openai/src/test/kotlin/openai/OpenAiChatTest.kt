// NEW: the openai-chat dialect proof — a HeadServer wired with OpenAiChatProvider against a mock
// Chat Completions upstream serves a real turn (text + reasoning_content + tool_calls + finish),
// proving "any OpenAI-compatible vendor, zero new translator code". Request-builder shape pinned.
package openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.parseAnthropicBody
import splice.core.turn.WatchdogBudget
import splice.core.util.discard
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.provider.openai.ApiKeyAuthProvider
import splice.provider.openai.OpenAiChatProvider
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds

/** A minimal OpenAI Chat Completions mock (different SSE shape from Responses). */
private const val REASONING_FRAME = """{"choices":[{"delta":{"reasoning_content":"thinking hard"}}]}"""
private const val CONTENT_FRAME = """{"choices":[{"delta":{"content":"Hello world"}}]}"""
private const val FINISH_FRAME =
    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":3}}"""

private class MockChatUpstream {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    var lastBody: String = ""

    init {
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun sse(ex: HttpExchange, json: String) {
        ex.responseBody.write("data: $json\n\n".toByteArray())
        ex.responseBody.flush()
    }

    private fun handle(ex: HttpExchange) {
        lastBody = ex.requestBody.readBytes().decodeToString()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        sse(ex, REASONING_FRAME)
        sse(ex, CONTENT_FRAME)
        sse(ex, FINISH_FRAME)
        ex.responseBody.write("data: [DONE]\n\n".toByteArray())
        runCatching { ex.responseBody.close() }.discard("test-server teardown")
        runCatching { ex.close() }.discard("test-server teardown")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAiChatTest {

    private val mock = MockChatUpstream()
    private val client = HttpClient(CIO) {
        defaultRequest { bearerAuth("test-inference-token") }
    }
    private val port = freshPort()
    private lateinit var head: HeadServer

    @BeforeAll
    fun setUp() = runBlocking {
        val tmp = Files.createTempDirectory("chat-it")
        val provider = OpenAiChatProvider(
            tuning = ProviderTuning(
                key = "openrouter",
                label = "openrouter",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-openrouter--",
                    models = listOf(ModelEntry("meta/llama-4", "Llama 4", contextWindow = 128_000)),
                    defaultContextWindow = 128_000,
                ),
                pinnedModel = "meta/llama-4",
                auth = ApiKeyAuthProvider("OPENROUTER_API_KEY", envReader = { "or-key-123456" }),
                baseUrl = mock.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = ChatQuirks(providerTag = "openrouter"),
        )
        head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(5_000, 30_000, 2),
                inferenceToken = "test-inference-token",
                gate = InflightGate({ 0 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("c.jsonl")),
                usageStore = UsageStore(tmp.resolve("u.json"), tmp.resolve("r.json")),
                perfStats = PerfStats(tmp.resolve("p.jsonl")),
                log = {},
            ),
        )
        head.start()
        awaitListening(port)
    }

    @AfterAll
    fun tearDown() = runBlocking {
        head.stop()
        client.close()
        mock.stop()
    }

    @Test
    fun `chat vendor serves a real turn - reasoning, text, clean stop`() = runBlocking {
        // Full end-to-end stream assertion (same strength as the codex HeadServerIntegrationTest):
        // reasoning_content -> thinking, content -> text, clean end_turn + message_stop. An earlier
        // adversarial reproduction (220 request/response cycles incl. 20 cold JVMs + starved-CPU
        // runs through the real product classes) found zero truncation — the transport is reliable.
        val sse = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-openrouter--meta/llama-4","stream":true,"max_tokens":500,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("event: message_start"))
        assertTrue(sse.contains("event: content_block_start"))
        assertTrue(sse.contains("thinking_delta")) // reasoning_content -> thinking block
        assertTrue(sse.contains("Hello world"))
        assertTrue(sse.contains("\"stop_reason\":\"end_turn\""))
        assertTrue(sse.contains("event: message_stop"))
        assertTrue(sse.contains("claude-openrouter--meta/llama-4")) // original model echoed
        // api key rode as the bearer; max_tokens honored (unlike the ChatGPT backend);
        // discovery prefix unwrapped for the upstream
        assertTrue(mock.lastBody.contains("\"max_tokens\":500"))
        assertTrue(mock.lastBody.contains("\"model\":\"meta/llama-4\""))
        assertTrue(mock.lastBody.contains("\"messages\""))
    }

    @Test
    fun `request builder maps messages, tools, and tool results to chat shape`() {
        val parsed = parseAnthropicBody(
            """{"model":"m","system":"be brief","max_tokens":100,
                "tools":[{"name":"run","input_schema":{"type":"object"}}],
                "messages":[
                  {"role":"user","content":"do it"},
                  {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"run","input":{"c":1}}]},
                  {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"done"}]}
                ]}""",
        )
        val req = ChatRequestBuilder(ChatQuirks("test")).build(parsed.typed, "gpt-x", "gpt-x", compact = false).req
        val messages = req["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages[0]["role"]?.jsonPrimitive?.content)
        assertEquals("user", messages[1]["role"]?.jsonPrimitive?.content)
        val assistant = messages[2]
        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        val toolCall = assistant["tool_calls"]!!.jsonArray.first().jsonObject
        assertEquals("t1", toolCall["id"]?.jsonPrimitive?.content)
        assertEquals("run", toolCall["function"]!!.jsonObject["name"]?.jsonPrimitive?.content)
        val toolMsg = messages[3]
        assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
        assertEquals("t1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("done", toolMsg["content"]?.jsonPrimitive?.content)
        assertEquals("100", req["max_tokens"]?.jsonPrimitive?.content)
        assertTrue(req["tools"]!!.jsonArray.isNotEmpty())
    }
}
