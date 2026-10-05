package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import splice.codemode.DEFAULT_WORKER_START_TIMEOUT_MS
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.TurnBill
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.websocket.WsConnector
import splice.dialect.responses.websocket.WsUpstream
import splice.head.HeadDeps
import splice.head.HeadEvents
import splice.head.HeadServer
import splice.head.NoHeadEvents
import splice.head.headDeps
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeSessionAlive
import splice.provider.codex.CodeModeStateLocation
import splice.provider.codex.CodexCodeModeBridge
import splice.provider.codex.CodexProvider
import splice.upstream.Provider
import splice.upstream.ProviderTuning
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.retry.InflightGate
import splice.upstream.sse.SseReader
import java.io.IOException
import java.net.InetSocketAddress
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

// why: the head's pinned model, priced at a synthetic card so a double count shows up as spend.
private const val MODEL = "gpt-5.6-sol"
private val PRICED = ModelCatalog(
    discoveryPrefix = "claude-codex--",
    models = listOf(ModelEntry(MODEL, contextWindow = 272_000, rates = ModelRates(1.0, 0.1, 4.0))),
    defaultContextWindow = 272_000,
)

// why: dollars summed over three rows; a double-counted round moves the sum by whole cents, far past this.
private const val SPEND_EPSILON = 1e-12

// why: the first turn boots a real code-mode worker JVM, which the product allows its own bound to start.
private const val BILLING_TEST_SECONDS = 60L
private const val TURN_BOUND_MS: Long = DEFAULT_WORKER_START_TIMEOUT_MS + 5_000L

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

    /** Oct 4, 11:15 AM CT: a parked claudex turn wrote 0 in, 0 out, and a compaction 15 minutes later carried its
     *  377k-token round as absorbed. Here the source round ends only after the turn that posted it has returned. */
    @ParameterizedTest
    @CsvSource(
        "false,unknown,false", "true,unknown,false",
        "false,alive,false", "true,alive,false",
        "false,gone,false", "true,gone,false",
        "false,unknown,true", "true,unknown,true",
        "false,alive,true", "true,alive,true",
        "false,gone,true", "true,gone,true",
    )
    @Timeout(BILLING_TEST_SECONDS)
    fun `a round still streaming when its tool call left is billed on the turn that posted it, and only there`(
        webSocket: Boolean,
        liveness: String,
        reminder: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true)
        val ws = BillingWsRunner()
        val runtime = StatementGatewayRuntime()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                sessionAlive = CodeModeSessionAlive { liveness.takeUnless { it == "unknown" }?.let { it == "alive" } },
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws.takeIf { webSocket }), 0, headDeps(tmp))
        var client = HttpClient(CIO) {
            engine { requestTimeout = TimeUnit.SECONDS.toMillis(BILLING_TEST_SECONDS) }
        }
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture twice")))
            val parked = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            assertTrue(parked.contains("\"name\":\"Read\""), parked)
            assertTrue(parked.contains("message_stop"), parked)
            client = HttpClient(CIO)
            assertEquals(0, ws.aborts.get(), "the posting client step must not cut its live WS source")
            history += message("assistant", JsonArray(toolUses(parked)))
            history += returnedMessages(parked, reminder)
            val step = continueBeforeTerminal(client, url, history, runtime) {
                assertEquals(0, ws.aborts.get(), "a matching continuation must not cut its live WS source")
                upstream.endSource()
                ws.endSource()
            }
            assertTrue(step.contains("\"name\":\"Read\""), step)
            assertTrue(step.contains("\"cache_read_input_tokens\":$SOURCE_CACHED"), "the round's context: $step")

            history += message("assistant", JsonArray(toolUses(step)))
            history += message("user", JsonArray(toolUses(step).map(::result)))
            val answer = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(answer.contains("fixture read"), answer)
            assertEquals(2, if (webSocket) ws.posts.get() else upstream.posts.get(), "one source and one continuation")
            if (webSocket) {
                assertWsReuse(ws, upstream)
            }

            assertParkedRows(rows(tmp, 3))
        } finally {
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a script failure before the WS terminal must preserve its source bill before continuing`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner("await tools.Read({});\nthrow new Error('synthetic script failure');\n")
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws), 0, headDeps(tmp))
        var client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("return the fixture result")))
            val first = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            assertTrue(first.contains("message_stop"), first)
            assertEquals(1, toolUses(first).size, "a real client tool was dispatched before the script failed")
            client = HttpClient(CIO)
            history += message("assistant", JsonArray(toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val next = async { send(client, url, history) }
            withTimeout(TURN_BOUND_MS) { runtime.delivered.receive() }
            assertNull(withTimeoutOrNull(1_000) { next.await() }, "the script must wait for its uncompleted source")
            assertEquals(0, ws.aborts.get(), "a returned tool result must not abort its billing source")
            ws.endSource()
            assertTrue(withTimeout(TURN_BOUND_MS) { next.await() }.contains("fixture read"))
            assertTrue(runtime.completed.await().error.orEmpty().contains("synthetic script failure"))
            val posting = rows(tmp, 2).single { it.count(PerfKeys.IN_TOKENS) == SOURCE_INPUT }
            assertEquals(SOURCE_OUTPUT, posting.count(PerfKeys.OUT_TOKENS), "$posting")
            assertWsReuse(ws, upstream)
        } finally {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(BILLING_TEST_SECONDS)
    fun `assistant prose delivered with a tool call cannot interrupt its live source when replayed`(
        webSocket: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true, prose = true)
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner(prose = true)
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws.takeIf { webSocket }), 0, headDeps(tmp))
        var client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the synthetic fixture")))
            val first = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            assertTrue(first.contains("synthetic progress"), "the client really received assistant prose: $first")
            client = HttpClient(CIO)
            history += message("assistant", JsonArray(listOf(text("synthetic progress")) + toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val next = async { send(client, url, history) }
            val delivered = withTimeoutOrNull(1_000) { runtime.delivered.receive() }
            assertEquals(0, ws.aborts.get(), "replaying splice-delivered assistant prose must not cut its source")
            assertTrue(delivered != null, "the issued tool result must reach the retained script")
            upstream.endSource()
            ws.endSource()
            val step = withTimeout(TURN_BOUND_MS) { next.await() }
            history += message("assistant", JsonArray(toolUses(step)))
            history += message("user", JsonArray(toolUses(step).map(::result)))
            assertTrue(withTimeout(TURN_BOUND_MS) { send(client, url, history) }.contains("fixture read"))
            assertParkedRows(rows(tmp, 3))
            if (webSocket) assertWsReuse(ws, upstream)
        } finally {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private fun returnedMessages(wire: String, reminder: Boolean): List<JsonObject> {
        val returned = message("user", JsonArray(toolUses(wire).map(::result)))
        val context = message("system", JsonPrimitive("<system-reminder>synthetic context</system-reminder>"))
        return if (reminder) listOf(returned, context) else listOf(returned)
    }

    private suspend fun continueBeforeTerminal(
        client: HttpClient,
        url: String,
        history: List<JsonObject>,
        runtime: StatementGatewayRuntime,
        endSource: () -> Unit,
    ): String = coroutineScope {
        val continuation = async { send(client, url, history) }
        withTimeout(TURN_BOUND_MS) { runtime.delivered.receive() }
        assertNull(withTimeoutOrNull(100) { continuation.await() }, "the live source still owes its next statement")
        endSource()
        withTimeout(TURN_BOUND_MS) { continuation.await() }
    }

    private fun assertParkedRows(rows: List<JsonObject>) {
        val (posting, local, finishing) = rows.sortedBy { it.count("ts") }
        assertEquals(SOURCE_INPUT, posting.count(PerfKeys.IN_TOKENS), "$posting")
        assertEquals(SOURCE_OUTPUT, posting.count(PerfKeys.OUT_TOKENS), "$posting")
        assertEquals(SOURCE_CACHED, posting.count(PerfKeys.CACHED_TOKENS), "$posting")
        assertEquals(0L, local.count(PerfKeys.IN_TOKENS), "$local")
        assertEquals(ANSWER_INPUT, finishing.count(PerfKeys.IN_TOKENS), "$finishing")
        assertEquals(ANSWER_OUTPUT, finishing.count(PerfKeys.OUT_TOKENS), "$finishing")
        assertBilledOnce(listOf(posting, local, finishing))
    }

    /** Steering a parked script cuts its source round before the backend's terminal, so the tokens that round used
     *  are billed upstream and never reach splice. The turn that cut it counts the round, and no row claims tokens
     *  it never saw. */
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a source round cut by steering is counted on the turn that cut it, and its tokens on none`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true)
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
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture twice")))
            val parked = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(parked.contains("\"name\":\"Read\""), parked)

            history += message("assistant", JsonArray(toolUses(parked)))
            history += message("user", JsonArray(toolUses(parked).map(::result) + text("never mind, stop")))
            val steered = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(steered.contains("fixture read"), steered)
            assertEquals(2, upstream.posts.get(), "one source round, cut, and one continuation")

            val (posting, steering) = rows(tmp, 2).sortedBy { it.count("ts") }
            assertNull(posting[PerfKeys.CUT_SOURCE_ROUNDS], "the posting turn cut nothing: $posting")
            assertNull(posting[PerfKeys.IN_TOKENS], "a posted source without terminal usage is unreported: $posting")
            assertNull(posting[PerfKeys.OUT_TOKENS], "the source was cut before its output was reported: $posting")
            assertNull(posting[PerfKeys.LOCAL_STEP], "the source turn actually posted upstream: $posting")
            assertEquals(1L, steering.count(PerfKeys.CUT_SOURCE_ROUNDS), "the steering turn cut the round: $steering")
            assertEquals(ANSWER_INPUT, steering.count(PerfKeys.IN_TOKENS), "$steering")
            assertNull(steering[PerfKeys.ABSORBED_ROUNDS], "$steering")
        } finally {
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    /** The source round keeps its admission slot past the parked turn's message_stop and gives it back when it ends,
     *  as it did before rows were held (IndependentSourcePost). The held row is written after that, so a write that
     *  blocks or throws holds nothing: the slot is already free while the write is still stuck. */
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(BILLING_TEST_SECONDS)
    fun `the slot frees when the source round ends, even while the held row's write is stuck`(
        throws: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true)
        val runtime = StatementGatewayRuntime()
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val gate = InflightGate({ 1 })
        val events = StuckRowEvents(throws)
        val deps = headDeps(tmp, gate = gate, seams = HeadDeps.HeadSeams(events = events))
        val head = HeadServer(provider(upstream.url, bridge), 0, deps)
        val client = HttpClient(CIO) {
            engine { requestTimeout = TimeUnit.SECONDS.toMillis(BILLING_TEST_SECONDS) }
        }
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val first = listOf(message("user", JsonPrimitive("read the fixture twice")))
            val parked = withTimeout(TURN_BOUND_MS) { send(client, url, first) }
            assertTrue(parked.contains("message_stop"), parked)
            assertEquals(1, gate.snapshot().inflight, "the source round keeps its admission past message_stop")

            events.arm()
            upstream.endSource()
            assertTrue(events.entered.await(TURN_BOUND_MS, TimeUnit.MILLISECONDS), "the held row is written")
            withTimeout(TURN_BOUND_MS) { while (gate.snapshot().inflight != 0) delay(GATE_POLL_MS) }
            assertEquals(1L, events.stuck.count, "the slot came back while the row's write was still stuck")
            events.release()
            assertEquals(SOURCE_INPUT, rows(tmp, 1).single().count(PerfKeys.IN_TOKENS), "the round reached its row")
        } finally {
            events.release()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }
}

class CodeModeSourceBoundaryTest {
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `prose before consecutive dispatches stays owned and durable while the raw round is live`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner(prose = true)
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws), 0, headDeps(tmp))
        var client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture twice")))
            val first = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            assertPersistedProse(tmp, "synthetic progress")
            client = HttpClient(CIO)
            history += message("assistant", JsonArray(listOf(text("synthetic progress")) + toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val second = async { send(client, url, history) }
            withTimeout(TURN_BOUND_MS) { runtime.delivered.receive() }
            ws.nextStatement()
            val step = withTimeout(TURN_BOUND_MS) { second.await() }
            assertTrue(step.contains("synthetic follow-up"), step)
            assertEquals(1, toolUses(step).size, "the same script dispatched its second call before terminal")
            history += message("assistant", JsonArray(listOf(text("synthetic follow-up")) + toolUses(step)))
            history += message("user", JsonArray(toolUses(step).map(::result)))
            val answer = async { send(client, url, history) }
            val delivered = withTimeoutOrNull(1_000) { runtime.delivered.receive() }
            assertEquals(0, ws.aborts.get(), "the second echoed prose must not cut its retained source")
            assertTrue(delivered != null, "the second result must reach the same script")
            assertPersistedProse(tmp, "synthetic follow-up")
            ws.endSource()
            assertTrue(withTimeout(TURN_BOUND_MS) { answer.await() }.contains("fixture read"))
            assertBilledOnce(rows(tmp, 3))
            assertWsReuse(ws, upstream)
            assertSameUpstreamBytes(tmp, ws)
        } finally {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(BILLING_TEST_SECONDS)
    fun `partial results preserve the reader but an unowned result intentionally interrupts it`(
        unmapped: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner("await Promise.all([tools.Read({}), tools.Read({})]);\n")
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws), 0, headDeps(tmp))
        var client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read both synthetic fixtures")))
            val first = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            val calls = toolUses(first)
            assertEquals(2, calls.size)
            client = HttpClient(CIO)
            history += message("assistant", JsonArray(calls))
            val foreign = JsonObject(calls.first() + ("id" to JsonPrimitive("toolu_splice_unmapped")))
            val supplied = if (unmapped) calls.map(::result) + result(foreign) else listOf(result(calls.first()))
            val malformed = history + message("user", JsonArray(supplied))
            val rejected = withTimeout(TURN_BOUND_MS) { send(client, url, malformed) }
            if (unmapped) {
                assertTrue(rejected.contains("fixture read"), rejected)
                assertEquals(1, ws.aborts.get(), "unowned client content is a genuine interruption")
            } else {
                assertTrue(rejected.contains("missing code-mode tool results"), rejected)
                assertEquals(0, ws.aborts.get(), "a missing result alone must not cancel the source")
                finishPartialResult(client, url, history, calls, ws)
                val posted = rows(tmp, 4).single { it.count(PerfKeys.IN_TOKENS) == SOURCE_INPUT }
                assertEquals(SOURCE_OUTPUT, posted.count(PerfKeys.OUT_TOKENS))
                assertWsReuse(ws, upstream)
            }
        } finally {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private suspend fun finishPartialResult(
        client: HttpClient,
        url: String,
        history: MutableList<JsonObject>,
        calls: List<JsonObject>,
        ws: BillingWsRunner,
    ) {
        history += message("user", JsonArray(calls.map(::result)))
        ws.endSource()
        val second = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
        assertEquals(2, toolUses(second).size, "the retained script dispatches its second batch")
        history += message("assistant", JsonArray(toolUses(second)))
        history += message("user", JsonArray(toolUses(second).map(::result)))
        assertTrue(withTimeout(TURN_BOUND_MS) { send(client, url, history) }.contains("fixture read"))
    }

    private suspend fun assertSameUpstreamBytes(tmp: Path, ws: BillingWsRunner) {
        assertArrayEquals(
            completedSourceBody(tmp.resolve("baseline")),
            ws.requests.last().toByteArray(),
            "upstream bytes must not depend on splitting the same model prose between client steps",
        )
    }

    private suspend fun completedSourceBody(tmp: Path): ByteArray {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner(prose = true, whole = true)
        val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            ),
        )
        val head = HeadServer(withWs(upstream.url, bridge, ws), 0, headDeps(tmp))
        val client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture twice")))
            val first = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(first.contains("synthetic progress") && first.contains("synthetic follow-up"), first)
            assertEquals(SOURCE_INPUT, rows(tmp, 1).single().count(PerfKeys.IN_TOKENS))
            val prose = text("synthetic progresssynthetic follow-up")
            history += message("assistant", JsonArray(listOf(prose) + toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val second = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            history += message("assistant", JsonArray(toolUses(second)))
            history += message("user", JsonArray(toolUses(second).map(::result)))
            assertTrue(withTimeout(TURN_BOUND_MS) { send(client, url, history) }.contains("fixture read"))
            assertWsReuse(ws, upstream)
            return ws.requests.last().toByteArray()
        } finally {
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private fun assertPersistedProse(tmp: Path, prose: String) {
        val expected = "\"deliveredText\":${JsonPrimitive(prose)}"
        val saved = Files.list(tmp.resolve("records")).use { paths ->
            paths.anyMatch { Files.isRegularFile(it) && Files.readString(it).contains(expected) }
        }
        assertTrue(saved, "prose continuity must be durable before its client call is published: $prose")
    }
}

/** No row carries a round's tokens a second time, as tokens or as absorbed rounds, nor prices it twice. */
private fun assertBilledOnce(rows: List<JsonObject>) {
    rows.forEach { assertNull(it[PerfKeys.ABSORBED_ROUNDS], "a carried round is never absorbed: $it") }
    val tokens = rows.sumOf { TurnBill.total(counters(it)).let { b -> b.input + b.cacheRead + b.cacheWrite } }
    assertEquals(SOURCE_INPUT + ANSWER_INPUT, tokens, "every request's input once: $rows")
    assertEquals(SOURCE_OUTPUT + ANSWER_OUTPUT, rows.sumOf { it.count(PerfKeys.OUT_TOKENS) ?: 0L }, "$rows")
    val price = TurnPrice(PRICED)
    val spent = rows.map { checkNotNull(price.usd(MODEL, counters(it))) { "$it" } }
    val source = mapOf(
        PerfKeys.IN_TOKENS to SOURCE_INPUT,
        PerfKeys.CACHED_TOKENS to SOURCE_CACHED,
        PerfKeys.OUT_TOKENS to SOURCE_OUTPUT,
    )
    val answered = mapOf(PerfKeys.IN_TOKENS to ANSWER_INPUT, PerfKeys.OUT_TOKENS to ANSWER_OUTPUT)
    val expected = listOf(price.usd(MODEL, source), 0.0, price.usd(MODEL, answered)).map(::checkNotNull)
    expected.sorted().zip(spent.sorted()).forEach { (want, got) -> assertEquals(want, got, SPEND_EPSILON, "$spent") }
}

private fun counters(row: JsonObject): Map<String, Long> =
    row.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.longOrNull?.let { key to it } }.toMap()

private suspend fun send(client: HttpClient, url: String, history: List<JsonObject>): String =
    client.post(url) {
        bearerAuth("test-inference-token")
        headers.append("x-claude-code-session-id", "billing-session")
        setBody(body(JsonArray(history).toString()))
    }.bodyAsText()

/** Like a client that closes at message_stop, without waiting for the HTTP response's EOF. */
private suspend fun sendAndDisconnect(client: HttpClient, url: String, history: List<JsonObject>): String =
    client.preparePost(url) {
        bearerAuth("test-inference-token")
        headers.append("x-claude-code-session-id", "billing-session")
        setBody(body(JsonArray(history).toString()))
    }.execute { response ->
        val channel = response.bodyAsChannel()
        val frames = mutableListOf<JsonObject>()
        try {
            SseReader().sseJsonEvents(channel).onEach { frames += it }
                .first { it["type"]?.jsonPrimitive?.content == "message_stop" }
            frames.joinToString("\n\n") { "data: $it" }
        } finally {
            channel.cancel(null)
            client.close()
        }
    }

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

private fun withWs(url: String, bridge: CodexCodeModeBridge, runner: WsRoundRunner?): Provider {
    val delegate = provider(url, bridge)
    return if (runner == null) {
        delegate
    } else {
        object : Provider by delegate {
            override val wsRunner: WsRoundRunner = runner
        }
    }
}

private fun assertWsReuse(ws: BillingWsRunner, upstream: BillingUpstream) {
    assertEquals(2, ws.cleanRounds.get(), "both WS terminals return healthy sockets to the pool")
    assertEquals(0, ws.aborts.get(), "the next client step continues the retained source")
    assertEquals(1, ws.connections.get(), "the clean source socket is pooled for the continuation")
    assertEquals(0, upstream.posts.get(), "the WS source is not reissued over SSE")
}

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

// why: the perf row lands as the turn finishes; a short poll keeps the test from racing it.
private const val ROW_POLL_MS = 20L

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

/** A scripted WS source parks after a dispatchable prefix, before any terminal or usage exists. */
private class BillingWsRunner(
    private val source: String = "await tools.Read({});\n",
    private val prose: Boolean = false,
    private val whole: Boolean = false,
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
        val completed = """{"type":"response.completed","response":{"id":"source-response","status":"completed",
            "usage":{"input_tokens":$SOURCE_INPUT,"output_tokens":$SOURCE_OUTPUT,
            "input_tokens_details":{"cached_tokens":$SOURCE_CACHED}},"output":[{
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
        if (prose) {
            val textItem = """{"type":"response.output_item.added","output_index":1,"item":{
                "type":"message","id":"progress-item","role":"assistant","content":[]}}"""
            val textDelta = """{"type":"response.output_text.delta","output_index":1,"content_index":0,
                "item_id":"progress-item","delta":"synthetic progress"}"""
            listener.emit(socket, textItem)
            listener.emit(socket, textDelta)
        }
        val added = """{"type":"response.output_item.added","output_index":0,"item":{
            "type":"custom_tool_call","id":"source-item","call_id":"source-call","name":"exec","input":""}}"""
        val delta = """{"type":"response.custom_tool_call_input.delta","output_index":0,
            "delta":${JsonPrimitive("${source}await ")}}"""
        listener.emit(socket, added)
        listener.emit(socket, delta)
        if (whole) {
            nextStatement()
            endSource()
        }
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
                if (posts.incrementAndGet() == 1) source(this) else answer(this)
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

/** Head events whose first turnEnded after [arm] is stuck: it blocks until [release], or throws. Every other event,
 *  and every turnEnded before [arm], passes through as nothing. */
private class StuckRowEvents(private val throws: Boolean) : HeadEvents by NoHeadEvents {
    val entered = CountDownLatch(1)
    val stuck = CountDownLatch(1)

    @Volatile private var armed = false

    fun arm() {
        armed = true
    }

    fun release() = stuck.countDown()

    override fun turnEnded(perfRowId: String, outcome: String, session: String?) {
        if (!armed) return
        armed = false
        entered.countDown()
        check(!throws) { "synthetic failure writing the held row" }
        stuck.await(BILLING_TEST_SECONDS, TimeUnit.SECONDS)
    }
}
