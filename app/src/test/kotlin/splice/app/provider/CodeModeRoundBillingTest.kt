package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import splice.codemode.WORKER_START_TIMEOUT_MS
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ReasoningSettings
import splice.dialect.responses.websocket.WsConnector
import splice.dialect.responses.websocket.WsUpstream
import splice.head.HeadServer
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexCodeModeWiring
import splice.provider.codex.CodexProvider
import splice.upstream.Provider
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
import splice.upstream.codemode.CodeModeSourcePart
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
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
private const val BILLING_TEST_SECONDS = 180L
private const val TURN_BOUND_MS: Long = WORKER_START_TIMEOUT_MS + 5_000L

// How often the slot test reads the admission gate while it waits for the source round's permit to return.
private const val GATE_POLL_MS = 10L

/**
 * Oct 4: a claudex turn whose script called a client tool recorded zero tokens, and the next turn of the same
 * script carried this round's tokens instead of its own. The round that writes a script is billed when the
 * script finishes, because it can still be streaming when its first tool call leaves. One that had already
 * finished by then was billed late all the same, though its usage was in hand when the turn's row was written.
 */
class CodeModeRoundBillingTest {
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a completed content stream reports its known bill or leaves unreported tokens absent`(
        @TempDir tmp: Path,
    ) = runBlocking {
        for (reply in listOf(BillingReply.CONTENT, BillingReply.CONTENT_WITHOUT_USAGE)) {
            val directory = tmp.resolve(reply.name)
            val upstream = BillingUpstream(firstReply = reply)
            val runtime = StatementGatewayRuntime()
            val bridge = CodexCodeModeBridge(
                CodeModeBridgeConfig(
                    runtimes = { runtime },
                    state = CodeModeStateLocation(directory.resolve("records"), directory.resolve("legacy.json")),
                ),
            )
            val head = HeadServer(provider(upstream.url, bridge), 0, headDeps(directory))
            val client = HttpClient(CIO)
            try {
                head.start()
                val history = listOf(message("user", JsonPrimitive("answer without calling a tool")))
                val answer = withTimeout(TURN_BOUND_MS) {
                    send(client, "http://127.0.0.1:${head.port}/v1/messages", history)
                }
                assertTrue(answer.contains("fixture read"), answer)
                assertTrue(answer.contains("message_stop"), answer)
                assertEquals(1, upstream.posts.get(), "a completed content stream is never rerun")
                val row = rows(directory, 1).single()
                assertTrue(checkNotNull(row.count(PerfKeys.UPSTREAM_REQ_BYTES)) > 0, "$row")
                assertEquals("ok", row.getValue("outcome").jsonPrimitive.content, "$row")
                assertNull(row[PerfKeys.LOCAL_STEP], "a completed content stream actually posted: $row")
                if (reply == BillingReply.CONTENT) {
                    assertEquals(SOURCE_INPUT, row.count(PerfKeys.IN_TOKENS), "$row")
                    assertEquals(SOURCE_OUTPUT, row.count(PerfKeys.OUT_TOKENS), "$row")
                    assertEquals(SOURCE_CACHED, row.count(PerfKeys.CACHED_TOKENS), "$row")
                } else {
                    assertNull(row[PerfKeys.IN_TOKENS], "completed content without usage is unreported: $row")
                    assertNull(row[PerfKeys.OUT_TOKENS], "$row")
                    assertNull(row[PerfKeys.CACHED_TOKENS], "$row")
                }
            } finally {
                head.stop()
                runtime.close()
                client.close()
                upstream.close()
            }
        }
    }

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
}

/** A rejected first admission never parks or holds a posting row, but still owns its actual raw post. */
class CodeModeFirstStepBillingTest {
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a first step rejected before its source terminal counts one unreported cut`(@TempDir tmp: Path) = runBlocking {
        rejectFirstStep(tmp, reported = false)
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a rejected first step keeps its already reported raw tokens without a cut`(@TempDir tmp: Path) = runBlocking {
        rejectFirstStep(tmp, reported = true)
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a cancelled first step without an outcome still counts its source cut`(@TempDir tmp: Path) = runBlocking {
        rejectFirstStep(tmp, reported = false, CancellationException("synthetic first-step cancellation"))
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `head restart during first admission cancels its live source without counting a client cut`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream()
        val ws = BillingWsRunner()
        val runtime = FirstStepBillingRuntime(IOException("synthetic admission must remain pending"))
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val delegate = withWs(upstream.url, bridge, ws)
        val stopped = object : Provider by delegate {
            override val watchdog = WatchdogBudget(120.seconds, 120.seconds, 120.seconds)
        }
        val head = HeadServer(stopped, 0, headDeps(tmp))
        val client = HttpClient(CIO) {
            engine { requestTimeout = TimeUnit.SECONDS.toMillis(BILLING_TEST_SECONDS) }
        }
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val first = async {
                try {
                    send(client, url, listOf(message("user", JsonPrimitive("read the synthetic fixture"))))
                } catch (_: IOException) {
                    ""
                }
            }
            withTimeout(TURN_BOUND_MS) { runtime.started.await() }
            assertEquals(0, ws.aborts.get(), "restart must begin while source generation is still active")
            head.stop()
            val posting = rows(tmp, 1).single()
            assertHeadRestartBill(posting)
            assertEquals(1, ws.aborts.get())
            assertEquals(1, ws.posts.get())
            assertEquals(0, upstream.posts.get())
            first.cancelAndJoin()
        } finally {
            runtime.reject.complete(Unit)
            head.stop()
            client.close()
            upstream.close()
        }
    }

    private suspend fun rejectFirstStep(
        tmp: Path,
        reported: Boolean,
        failure: Exception = IOException("synthetic first-step rejection"),
    ) = coroutineScope {
        val upstream = BillingUpstream()
        val ws = BillingWsRunner()
        val runtime = FirstStepBillingRuntime(failure)
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws), 0, headDeps(tmp))
        val client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = listOf(message("user", JsonPrimitive("read the synthetic fixture")))
            val first = async { send(client, url, history) }
            withTimeout(TURN_BOUND_MS) { runtime.started.await() }
            if (reported) {
                ws.endSource()
                awaitCleanRounds(ws, 1)
                withTimeout(TURN_BOUND_MS) { runtime.sourceEnded() }
            }
            runtime.reject.complete(Unit)
            val answer = if (failure is CancellationException) {
                first.cancelAndJoin()
                ""
            } else {
                withTimeout(TURN_BOUND_MS) { first.await() }.also {
                    assertTrue(it.contains("source was not rerun"), it)
                }
            }
            assertTrue(toolUses(answer).isEmpty(), "the failed first step never publishes a parked callback")
            assertFirstStepBill(rows(tmp, 1).single(), reported)
            assertEquals(if (reported) 0 else 1, ws.aborts.get(), "only the unreported source is cancelled")
            assertEquals(1, ws.posts.get())
            assertEquals(0, upstream.posts.get(), "the synthetic WebSocket post is not reissued")
        } finally {
            runtime.reject.complete(Unit)
            head.stop()
            client.close()
            upstream.close()
        }
    }
}

/** Waits until the scripted socket reports [count] clean rounds, which is when the head has consumed the terminal. */
private suspend fun awaitCleanRounds(ws: BillingWsRunner, count: Int) =
    withTimeout(TURN_BOUND_MS) { while (ws.cleanRounds.get() < count) delay(GATE_POLL_MS) }

private fun assertHeadRestartBill(posting: JsonObject) {
    assertEquals(0L, posting.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L, "head restart is not a client cut: $posting")
    assertEquals("error:restarted", posting.getValue("outcome").jsonPrimitive.content, "$posting")
}

private fun assertFirstStepBill(posting: JsonObject, reported: Boolean) {
    assertEquals(if (reported) SOURCE_INPUT else 0L, posting.count(PerfKeys.IN_TOKENS) ?: 0L)
    assertEquals(if (reported) SOURCE_OUTPUT else 0L, posting.count(PerfKeys.OUT_TOKENS) ?: 0L)
    assertEquals(if (reported) SOURCE_CACHED else 0L, posting.count(PerfKeys.CACHED_TOKENS) ?: 0L)
    assertEquals(if (reported) 0L else 1L, posting.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L, "$posting")
}

private class FirstStepBillingRuntime(private val failure: Exception) : CodeModeRuntime {
    val started = CompletableDeferred<Unit>()
    val reject = CompletableDeferred<Unit>()
    private val held = CompletableDeferred<CodeModeSource>()

    /** Reads the source to its end. That end is made after the reader has settled its terminal, so a rejection sent
     *  once this returns can no longer cut a source that finished. */
    suspend fun sourceEnded() {
        val source = held.await()
        while (source.read().let { it !is CodeModeSourcePart.Complete && it !is CodeModeSourcePart.Failed }) Unit
    }

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        error("the synthetic first step must use its streaming source")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        held.complete(source)
        started.complete(Unit)
        reject.await()
        if (failure is CancellationException) currentCoroutineContext().cancel(failure)
        throw failure
    }

    override fun close() = Unit
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
    return withTimeout(ROW_BOUND_MS) {
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

private fun text(words: String): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", words)
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

private fun withWs(
    url: String,
    bridge: CodexCodeModeBridge,
    runner: WsRoundRunner?,
    replayReasoning: Boolean = false,
): Provider {
    val delegate = provider(url, bridge, replayReasoning)
    return if (runner == null) {
        delegate
    } else {
        object : Provider by delegate {
            override val wsRunner: WsRoundRunner = runner
        }
    }
}

private fun provider(
    url: String,
    bridge: CodexCodeModeBridge,
    replayReasoning: Boolean = false,
): CodexProvider = CodexProvider(
    tuning = ProviderTuning(
        name = ProviderName(key = "codex", label = "billing-test"),
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
        locations = ProviderLocations(baseUrl = url),
        // why: a ceiling, never a pace; a loaded machine still boots the real worker JVM inside it.
        watchdog = WatchdogBudget(120.seconds, 120.seconds, 120.seconds),
    ),
    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, replayReasoning, null, null),
    codeMode = CodexCodeModeWiring(bridge = bridge, models = listOf("gpt-5.6-sol")),
    log = {},
)

// why: the perf row lands as the turn finishes; a short poll keeps the test from racing it.
private const val ROW_POLL_MS = 20L

// why: a ceiling the row beats; a turn that ends by cancellation lands its row late on a loaded machine.
private const val ROW_BOUND_MS = 150_000L

private enum class BillingReply { SOURCE, CONTENT, CONTENT_WITHOUT_USAGE }

// The completed-content attack uses the observed frame count, without a live response or timing-dependent sleeps.
private const val COMPLETED_CONTENT_FRAMES = 58

/** A loopback Responses backend. Its source round ends on its one statement, so the round's terminal, and its
 *  usage, arrive before the script's tool call can leave: the call is certified only at the end of the source. */
private class BillingUpstream(
    private val held: Boolean = false,
    private val firstReply: BillingReply = BillingReply.SOURCE,
    private val prose: Boolean = false,
) {
    val posts = AtomicInteger()
    private val source = "await tools.Read({});\n"
    private val ended = CountDownLatch(1)
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
        exchange.responseBody.use { output ->
            when {
                attempt > 1 -> answer(output)
                firstReply == BillingReply.SOURCE -> source(output)
                else -> completedContent(output)
            }
        }
    }

    /** Lets a held source round reach its terminal. */
    fun endSource() = ended.countDown()

    /** A held round streams its first statement and the token that ends it (the parser holds a final statement
     *  until a following token rules out a continuation, as a model's next statement does), then waits for
     *  [endSource]. The rest of its second statement arrives only with the terminal, so that call leaves after the
     *  round's usage is in. */
    private fun source(output: java.io.OutputStream) {
        val streamed = if (held) "${source}await " else source
        if (prose) {
            val textItem = """{"type":"response.output_item.added","output_index":1,"item":{
                "type":"message","id":"progress-item","role":"assistant","content":[]}}"""
            val textDelta = """{"type":"response.output_text.delta","output_index":1,"content_index":0,
                "item_id":"progress-item","delta":"synthetic progress"}"""
            event(output, textItem)
            event(output, textDelta)
        }
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}""",
        )
        event(
            output,
            """{"type":"response.custom_tool_call_input.delta","output_index":0,"delta":${JsonPrimitive(streamed)}}""",
        )
        if (held) check(ended.await(BILLING_TEST_SECONDS, TimeUnit.SECONDS)) { "the test never ended the source" }
        val input = if (held) source + source else source
        event(
            output,
            """{"type":"response.completed","response":{"id":"source-response","status":"completed",
                "usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
                "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"output":[{
                "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
                "input":${JsonPrimitive(input)}}]}}""",
        )
    }

    private fun completedContent(output: java.io.OutputStream) {
        event(
            output,
            """{"type":"response.output_item.added","output_index":0,"item":{
                "type":"message","id":"content-item","role":"assistant","content":[]}}""",
        )
        repeat(COMPLETED_CONTENT_FRAMES) {
            event(
                output,
                """{"type":"response.output_text.delta","output_index":0,"content_index":0,
                    "item_id":"content-item","delta":"fixture read"}""",
            )
        }
        val usage = if (firstReply == BillingReply.CONTENT) {
            """"usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
                "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"""
        } else {
            ""
        }
        val text = JsonPrimitive("fixture read".repeat(COMPLETED_CONTENT_FRAMES))
        event(
            output,
            """{"type":"response.completed","response":{"id":"content-response","status":"completed",
                $usage"output":[{"type":"message","id":"content-item","role":"assistant",
                "content":[{"type":"output_text","text":$text}]}]}}""",
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
        endSource()
        server.stop(0)
        pool.shutdownNow()
    }
}

private const val PRELUDE_COMMENTARY =
    "I will read the synthetic fixture first, preserve its returned values, and then continue the next script " +
        "without changing the earlier commentary."

private const val BILLING_REASONING = """{"type":"reasoning","id":"synthetic-reasoning",
    "encrypted_content":"synthetic-encrypted-content","summary":[]}"""

/** A scripted WS source parks after a dispatchable prefix, before any terminal or usage exists. */
private class BillingWsRunner(
    private val source: String = "await tools.Read({});\n",
    private val prose: Boolean = false,
    private val whole: Boolean = false,
    private val reasoning: Boolean = false,
    private val preludeCalls: Int = 0,
) : WsRoundRunner {
    val posts = AtomicInteger()
    val aborts = AtomicInteger()
    val cleanRounds = AtomicInteger()
    val connections = AtomicInteger()
    val requests = ConcurrentLinkedQueue<String>()
    private lateinit var active: WebSocket
    private lateinit var listener: WebSocket.Listener
    private var secondStatement = false
    private val transport = WsUpstream(connector = WsConnector { _, _, receiver -> socket(receiver) })

    fun endSource() {
        if (!::active.isInitialized) return
        val input = source + source + if (secondStatement) "text ('');\n" else ""
        val native = if (reasoning) "$BILLING_REASONING," else ""
        val completed = """{"type":"response.completed","response":{"id":"source-response","status":"completed",
            "usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
            "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"output":[$native{
            "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec",
            "input":${JsonPrimitive(input)}}]}}"""
        listener.emit(active, completed)
    }

    fun nextStatement() {
        secondStatement = true
        val item = """{"type":"response.output_item.added","output_index":2,"item":{
            "type":"message","id":"follow-up-item","role":"assistant","content":[]}}"""
        val text = """{"type":"response.output_text.delta","output_index":2,"content_index":0,
            "item_id":"follow-up-item","delta":"synthetic follow-up"}"""
        val code = """{"type":"response.custom_tool_call_input.delta","output_index":0,
            "delta":${JsonPrimitive("tools.Read({});\ntext (")}}"""
        listener.emit(active, item)
        listener.emit(active, text)
        listener.emit(active, code)
    }

    override suspend fun attempt(
        bodyJson: String,
        meta: TurnMeta,
        turnHeaders: Map<String, String>,
        creds: Credentials,
    ): WsRound? {
        requests += bodyJson
        return transport.round(
            key = "synthetic-billing-socket",
            headers = emptyMap(),
            wssUrl = "wss://example.invalid/responses",
            isTerminal = { it["type"]?.jsonPrimitive?.content == "response.completed" },
            frameFor = { bodyJson },
        )?.let { events ->
            WsRound(events, WsRoundAbort { listener.onError(active, IOException("synthetic WS round aborted")) })
        }
    }

    override fun isFailureTerminal(event: JsonObject): Boolean = false

    override fun roundEnded(meta: TurnMeta, ok: Boolean) {
        if (ok) cleanRounds.incrementAndGet()
    }

    override fun roundBypassed(meta: TurnMeta) = Unit

    private fun source(socket: WebSocket) {
        listener.emit(socket, """{"type":"response.created","response":{"id":"source-response"}}""")
        if (reasoning) {
            listener.emit(
                socket,
                """{"type":"response.output_item.added","output_index":0,"item":$BILLING_REASONING}""",
            )
            listener.emit(socket, """{"type":"response.output_item.done","output_index":0,"item":$BILLING_REASONING}""")
        }
        if (prose) {
            val textItem = """{"type":"response.output_item.added","output_index":1,"item":{
                "type":"message","id":"progress-item","role":"assistant","content":[]}}"""
            val textDelta = """{"type":"response.output_text.delta","output_index":1,"content_index":0,
                "item_id":"progress-item","delta":"synthetic progress"}"""
            listener.emit(socket, textItem)
            listener.emit(socket, textDelta)
        }
        val execIndex = if (reasoning) 1 else 0
        val added = """{"type":"response.output_item.added","output_index":$execIndex,"item":{
            "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}"""
        val delta = """{"type":"response.custom_tool_call_input.delta","output_index":$execIndex,
            "delta":${JsonPrimitive("${source}await ")}}"""
        listener.emit(socket, added)
        listener.emit(socket, delta)
        if (whole) {
            nextStatement()
            endSource()
        }
    }

    private fun prelude(socket: WebSocket) {
        val native = BILLING_REASONING.replace("synthetic-reasoning", "prelude-reasoning")
        val code = "await Promise.all([" + List(preludeCalls) { "tools.Read({})" }.joinToString(",") + "]);\n"
        listener.emit(socket, """{"type":"response.created","response":{"id":"prelude-response"}}""")
        listener.emit(socket, """{"type":"response.output_item.added","output_index":0,"item":$native}""")
        listener.emit(socket, """{"type":"response.output_item.done","output_index":0,"item":$native}""")
        listener.emit(
            socket,
            """{"type":"response.output_item.added","output_index":1,"item":{
                "type":"message","id":"prelude-prose","role":"assistant","content":[]}}""",
        )
        listener.emit(
            socket,
            """{"type":"response.output_text.delta","output_index":1,"content_index":0,
                "item_id":"prelude-prose","delta":${JsonPrimitive(PRELUDE_COMMENTARY)}}""",
        )
        listener.emit(
            socket,
            """{"type":"response.output_item.added","output_index":2,"item":{
                "type":"custom_tool_call","id":"prelude-item","call_id":"prelude-call","name":"exec","input":""}}""",
        )
        listener.emit(
            socket,
            """{"type":"response.custom_tool_call_input.delta","output_index":2,"delta":${JsonPrimitive(code)}}""",
        )
        listener.emit(
            socket,
            """{"type":"response.completed","response":{"id":"prelude-response","status":"completed",
                "usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
                "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"output":[$native,
                {"type":"message","id":"prelude-prose","role":"assistant","content":[
                {"type":"output_text","text":${JsonPrimitive(PRELUDE_COMMENTARY)}}]},
                {"type":"custom_tool_call","id":"prelude-item","call_id":"prelude-call","name":"exec",
                "input":${JsonPrimitive(code)}}]}}""",
        )
    }

    private fun answer(socket: WebSocket) {
        listener.emit(socket, """{"type":"response.created","response":{"id":"answer-response"}}""")
        val added = """{"type":"response.output_item.added","output_index":0,"item":{
            "type":"message","id":"answer-item","role":"assistant","content":[]}}"""
        val delta = """{"type":"response.output_text.delta","output_index":0,"content_index":0,
            "item_id":"answer-item","delta":"fixture read"}"""
        val completed = """{"type":"response.completed","response":{"id":"answer-response","status":"completed",
            "usage":{"input_tokens":$ANSWER_INPUT,"output_tokens":$ANSWER_OUTPUT},"output":[{
            "type":"message","id":"answer-item","role":"assistant",
            "content":[{"type":"output_text","text":"fixture read"}]}]}}"""
        listener.emit(socket, added)
        listener.emit(socket, delta)
        listener.emit(socket, completed)
    }

    private fun WebSocket.Listener.emit(socket: WebSocket, text: String) {
        onText(socket, text.lineSequence().joinToString(""), true)
    }

    private fun socket(receiver: WebSocket.Listener): WebSocket {
        connections.incrementAndGet()
        listener = receiver
        return object : WebSocket {
            override fun sendText(data: CharSequence, last: Boolean): CompletableFuture<WebSocket> {
                val attempt = posts.incrementAndGet()
                when {
                    preludeCalls > 0 && attempt == 1 -> prelude(this)
                    attempt == (if (preludeCalls > 0) 2 else 1) -> source(this)
                    else -> answer(this)
                }
                return CompletableFuture.completedFuture(this)
            }
            override fun sendBinary(
                data: ByteBuffer,
                last: Boolean,
            ) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendPing(message: ByteBuffer) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendPong(message: ByteBuffer) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun sendClose(statusCode: Int, reason: String) = CompletableFuture.completedFuture<WebSocket>(this)
            override fun request(n: Long) = Unit
            override fun getSubprotocol() = ""
            override fun isOutputClosed() = false
            override fun isInputClosed() = false
            override fun abort() { aborts.incrementAndGet() }
        }.also {
            active = it
            receiver.onOpen(it)
        }
    }
}
