package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.codemode.DEFAULT_WORKER_START_TIMEOUT_MS
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexProvider
import splice.upstream.ProviderTuning
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// why: the source round's own bill, as the backend reports it in response.completed.
private const val SOURCE_INPUT = 100L
private const val SOURCE_OUTPUT = 7L
private const val SOURCE_CACHED = 40L

// why: the continuation round that answers once the script has its result.
private const val ANSWER_INPUT = 11L
private const val ANSWER_OUTPUT = 3L

// why: the first turn boots a real code-mode worker JVM, which the product allows its own bound to start.
private const val BILLING_TEST_SECONDS = 60L
private const val TURN_BOUND_MS: Long = DEFAULT_WORKER_START_TIMEOUT_MS + 5_000L

/**
 * Oct 4: a claudex turn whose script called a client tool recorded zero tokens, and the next turn of the same
 * script carried this round's tokens instead of its own. The round that writes a script is billed when the
 * script finishes, because it can still be streaming when its first tool call leaves. One that had already
 * finished by then was billed late all the same, though its usage was in hand when the turn's row was written.
 */
class CodeModeRoundBillingTest {
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a round that finished before its tool call left is billed on the turn that posted it, and only there`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val head = HeadServer(provider(upstream.url, bridge), 0, headDeps(tmp))
        val client = HttpClient(CIO) {
            engine { requestTimeout = TimeUnit.SECONDS.toMillis(BILLING_TEST_SECONDS) }
        }
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture")))
            val first = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(first.contains("\"name\":\"Read\""), first)
            assertTrue(first.contains("message_stop"), first)
            val sourceRow = rows(tmp, 1).single()
            assertEquals(SOURCE_INPUT, sourceRow.count(PerfKeys.IN_TOKENS), "$sourceRow")
            assertEquals(SOURCE_OUTPUT, sourceRow.count(PerfKeys.OUT_TOKENS), "$sourceRow")
            assertEquals(SOURCE_CACHED, sourceRow.count(PerfKeys.CACHED_TOKENS), "$sourceRow")

            history += message("assistant", JsonArray(toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val second = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(second.contains("fixture read"), second)
            assertEquals(2, upstream.posts.get(), "one source round and one continuation")
            val answerRow = rows(tmp, 2).last()
            assertEquals(ANSWER_INPUT, answerRow.count(PerfKeys.IN_TOKENS), "$answerRow")
            assertEquals(ANSWER_OUTPUT, answerRow.count(PerfKeys.OUT_TOKENS), "$answerRow")
            assertNull(answerRow["absorbed_rounds"], "the source round is billed once, on its own turn: $answerRow")
        } finally {
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private suspend fun send(client: HttpClient, url: String, history: List<JsonObject>): String =
        client.post(url) {
            bearerAuth("test-inference-token")
            headers.append("x-claude-code-session-id", "billing-session")
            setBody(body(JsonArray(history).toString()))
        }.bodyAsText()

    /** The head's perf rows once [count] have landed; the row is written as its turn finishes. */
    private suspend fun rows(tmp: Path, count: Int): List<JsonObject> {
        val file = tmp.resolve("perf.jsonl")
        return withTimeout(TURN_BOUND_MS) {
            var found = emptyList<JsonObject>()
            while (found.size < count) {
                delay(ROW_POLL_MS)
                found = if (Files.exists(file)) {
                    Files.readAllLines(file).filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
                } else {
                    emptyList()
                }
            }
            found
        }
    }

    private fun JsonObject.count(key: String): Long? = this[key]?.jsonPrimitive?.long

    private fun message(role: String, content: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject {
            put("role", role)
            put("content", content)
        }

    private fun result(call: JsonObject): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_use_id", call.getValue("id"))
        put("content", "fixture contents")
    }

    private fun toolUses(wire: String): List<JsonObject> = wire.lineSequence()
        .filter { it.startsWith("data: ") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "content_block_start" }
        .map { it.getValue("content_block").jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "tool_use" }
        .map { JsonObject(it + ("input" to JsonObject(emptyMap()))) }
        .toList()

    private fun body(messages: String): String =
        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":64,"messages":$messages,
            "tools":[{"name":"Read","description":"Read a synthetic fixture","input_schema":{"type":"object"}}]}"""

    private fun provider(url: String, bridge: CodexCodeModeBridge): CodexProvider = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "billing-test",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Stream", contextWindow = 272_000)),
                defaultContextWindow = 272_000,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = object : RefreshableAuthProvider {
                override suspend fun credentials(): Credentials = Credentials.Bearer("test-token")
                override suspend fun refresh(): Credentials = credentials()
                override suspend fun describe(): AuthDescription = AuthDescription(true, "test")
            },
            baseUrl = url,
            watchdog = WatchdogBudget(10.seconds, 10.seconds, 20.seconds),
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = null,
        configSummary = null,
        codeModeBridge = bridge,
        codeModeModels = listOf("gpt-5.6-sol"),
        log = {},
    )
}

// why: the perf row lands as the turn finishes; a short poll keeps the test from racing it.
private const val ROW_POLL_MS = 20L

/** A loopback Responses backend. Its source round ends on its one statement, so the round's terminal, and its
 *  usage, arrive before the script's tool call can leave: the call is certified only at the end of the source. */
private class BillingUpstream {
    val posts = AtomicInteger()
    private val source = "await tools.Read({});\n"
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/responses", ::respond)
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        val attempt = posts.incrementAndGet()
        exchange.requestBody.use { it.readAllBytes() }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { output -> if (attempt == 1) source(output) else answer(output) }
    }

    private fun source(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
        )
        event(
            output,
            """{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":${JsonPrimitive(source)}}""",
        )
        event(
            output,
            """{"type":"response.completed","response":{"id":"source-response","status":"completed",
                "usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
                "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"output":[{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
                "input":${JsonPrimitive(source)}}]}}""",
        )
    }

    private fun answer(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"message","id":"answer-item","role":"assistant","content":[]}}""",
        )
        event(
            output,
            """{"type":"response.output_text.delta","output_index":0,"content_index":0,
                "item_id":"answer-item","delta":"fixture read"}""",
        )
        event(
            output,
            """{"type":"response.completed","response":{"id":"answer-response","status":"completed",
                "usage":{"input_tokens":$ANSWER_INPUT,"output_tokens":$ANSWER_OUTPUT},"output":[{
                "type":"message","id":"answer-item","role":"assistant",
                "content":[{"type":"output_text","text":"fixture read"}]}]}}""",
        )
    }

    private fun event(output: java.io.OutputStream, json: String) {
        output.write(("data: " + json.lineSequence().joinToString("") + "\n\n").toByteArray())
        output.flush()
    }

    fun close() {
        server.stop(0)
        pool.shutdownNow()
    }
}
