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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
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
import splice.core.reasoning.ReasoningReplay
import splice.core.turn.HeadStopSignal
import splice.core.turn.ReasoningDisplay
import splice.core.turn.SpliceNotice
import splice.core.turn.TurnMeta
import splice.core.turn.Usage
import splice.core.turn.WatchdogBudget
import splice.core.util.LogSink
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
import splice.upstream.RowRelease
import splice.upstream.WsRound
import splice.upstream.WsRoundAbort
import splice.upstream.WsRoundRunner
import splice.upstream.codemode.CodeModeCell
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeSource
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
        cutPostedSource(tmp, steering = true)
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a superseding request frees the held source and counts its cut exactly once`(
        @TempDir tmp: Path,
    ) = runBlocking {
        cutPostedSource(tmp, steering = false)
    }

    private suspend fun cutPostedSource(tmp: Path, steering: Boolean) {
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
            if (steering) {
                history += message("assistant", JsonArray(toolUses(parked)))
                history += message("user", JsonArray(toolUses(parked).map(::result) + text("never mind, stop")))
            } else {
                history += message("user", JsonPrimitive("never mind, stop"))
            }
            val answer = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(answer.contains("fixture read"), answer)
            assertEquals(2, upstream.posts.get(), "one cancelled source and one continuation")
            val (posting, cutting) = rows(tmp, 2).sortedBy { it.count("ts") }
            assertNull(posting[PerfKeys.CUT_SOURCE_ROUNDS], "the posting turn cut nothing: $posting")
            assertNull(posting[PerfKeys.IN_TOKENS], "source tokens were never reported: $posting")
            assertNull(posting[PerfKeys.OUT_TOKENS], "source tokens were never reported: $posting")
            assertNull(posting[PerfKeys.LOCAL_STEP], "the source turn actually posted upstream: $posting")
            assertEquals(1L, cutting.count(PerfKeys.CUT_SOURCE_ROUNDS), "only the cutting turn owns the cut: $cutting")
            assertEquals(ANSWER_INPUT, cutting.count(PerfKeys.IN_TOKENS), "$cutting")
            assertNull(cutting[PerfKeys.ABSORBED_ROUNDS], "$cutting")
            assertTrue(withTimeout(TURN_BOUND_MS) { send(client, url, history) }.contains("fixture read"))
            val totalCuts = rows(tmp, 3).sumOf { it.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L }
            assertEquals(1L, totalCuts, "a retry cannot recount the cut")
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

class CodeModeNativeSourceTest {
    @ParameterizedTest
    @CsvSource("false,none", "false,after", "false,between", "true,none", "true,after", "true,between")
    @Timeout(BILLING_TEST_SECONDS)
    fun `delivered native reasoning is not edited history before or after source terminal`(
        completed: Boolean,
        reminder: String,
        @TempDir tmp: Path,
    ) = runBlocking {
        val actual = nativeSource(tmp, completed, reminder)
        if (!completed) {
            assertArrayEquals(
                nativeSource(tmp.resolve("baseline"), completed = true, reminder),
                actual,
                "the next upstream request must be byte-identical whether the source terminal was held or already known",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["edited", "foreign"])
    @Timeout(BILLING_TEST_SECONDS)
    fun `edited and foreign native envelopes still interrupt an unfinished source`(
        alteration: String,
        @TempDir tmp: Path,
    ) = runBlocking {
        nativeSource(tmp, completed = false, reminder = "between", alteration)
        Unit
    }

    private suspend fun nativeSource(
        tmp: Path,
        completed: Boolean,
        reminder: String,
        alteration: String = "",
    ): ByteArray = coroutineScope {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val ws = BillingWsRunner(reasoning = true)
        val logs = ConcurrentLinkedQueue<String>()
        val bridge = nativeBridge(tmp, runtime, logs)
        val head = HeadServer(withWs(upstream.url, bridge, ws, replayReasoning = true), 0, headDeps(tmp))
        var client = HttpClient(CIO)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the fixture twice")))
            val first = withTimeout(TURN_BOUND_MS) { sendAndDisconnect(client, url, history) }
            val deliveredNative = nativeBlocks(first)
            val native = alteredNative(deliveredNative, alteration)
            assertEquals(1, native.size, "the client must actually receive its native reasoning envelope: $first")
            assertEquals(1, toolUses(first).size, "the held raw round must publish a real callback")
            assertPersistedNative(tmp, deliveredNative.single())
            client = HttpClient(CIO)
            if (completed) {
                ws.endSource()
                assertEquals(SOURCE_INPUT, rows(tmp, 1).single().count(PerfKeys.IN_TOKENS))
            }
            appendNativeEcho(history, native, toolUses(first), reminder)
            val next = async { send(client, url, history) }
            val delivered = withTimeoutOrNull(1_000) { runtime.delivered.receive() }
            if (alteration.isNotEmpty()) {
                assertNull(delivered, "an altered envelope cannot resume the original script")
                assertRejectedNative(tmp, ws, withTimeout(TURN_BOUND_MS) { next.await() })
                return@coroutineScope ws.requests.last().toByteArray()
            }
            assertNativeResumed(logs, ws, delivered != null)
            if (!completed) ws.endSource()
            val second = withTimeout(TURN_BOUND_MS) { next.await() }
            assertEquals(1, toolUses(second).size, "the original script continues after its terminal")
            history += message("assistant", JsonArray(toolUses(second)))
            history += message("user", JsonArray(toolUses(second).map(::result)))
            assertTrue(withTimeout(TURN_BOUND_MS) { send(client, url, history) }.contains("fixture read"))
            assertWsReuse(ws, upstream)
            assertBilledOnce(rows(tmp, 3))
            ws.requests.last().toByteArray()
        } finally {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    private fun nativeBridge(
        tmp: Path,
        runtime: StatementGatewayRuntime,
        logs: ConcurrentLinkedQueue<String>,
    ): CodexCodeModeBridge = CodexCodeModeBridge(
        CodeModeBridgeConfig(
            runtimes = { runtime },
            state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
            clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
            log = LogSink { logs += it },
        ),
    )

    private fun appendNativeEcho(
        history: MutableList<JsonObject>,
        native: List<JsonObject>,
        calls: List<JsonObject>,
        reminder: String,
    ) {
        if (reminder == "between") {
            history += message("assistant", JsonArray(native))
            history += message("system", JsonPrimitive("synthetic context notification"))
            history += message("assistant", JsonArray(calls))
        } else {
            history += message("assistant", JsonArray(native + calls))
        }
        history += message("user", JsonArray(calls.map(::result)))
        if (reminder == "after") history += message("system", JsonPrimitive("synthetic context notification"))
    }

    private fun assertNativeResumed(logs: ConcurrentLinkedQueue<String>, ws: BillingWsRunner, delivered: Boolean) {
        assertEquals(
            emptyList<String>(),
            logs.filter { "abandoned record" in it },
            "identical delivered native replay must not be abandoned: ${logs.toList()}",
        )
        assertEquals(0, ws.aborts.get(), "identical delivered native replay must not cut the raw source")
        assertTrue(delivered, "the callback result must reach the retained script")
    }

    private suspend fun assertRejectedNative(tmp: Path, ws: BillingWsRunner, answer: String) {
        assertEquals(1, ws.aborts.get(), "an edited or foreign native envelope must cut the source")
        assertTrue(answer.contains("fixture read"), "the client history continues upstream after interruption")
        val reported = rows(tmp, 2)
        val cut = reported.single { it.count(PerfKeys.CUT_SOURCE_ROUNDS) == 1L }
        val totalCuts = reported.sumOf { it.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L }
        assertEquals(1L, totalCuts, "the native cut is counted once")
        assertEquals(ANSWER_INPUT, cut.count(PerfKeys.IN_TOKENS), "the cutting request keeps only its own usage")
    }

    private fun assertPersistedNative(tmp: Path, block: JsonObject) {
        val item = checkNotNull(ReasoningReplay.decodeReasoningEnvelope(block.getValue("data").jsonPrimitive.content))
        val expected = "\"deliveredNative\":${JsonArray(listOf(item))}"
        val saved = Files.list(tmp.resolve("records")).use { paths ->
            paths.anyMatch { Files.isRegularFile(it) && Files.readString(it).contains(expected) }
        }
        assertTrue(saved, "the actual delivered native envelope must be durable before its callback is published")
    }

    private fun alteredNative(blocks: List<JsonObject>, alteration: String): List<JsonObject> {
        if (alteration.isEmpty()) return blocks
        return blocks.map { block ->
            val data = block.getValue("data").jsonPrimitive.content
            val item = checkNotNull(ReasoningReplay.decodeReasoningEnvelope(data))
            val key = if (alteration == "foreign") "id" else "encrypted_content"
            val changed = JsonObject(item + (key to JsonPrimitive("synthetic-altered")))
            val envelope = checkNotNull(ReasoningReplay.encodeReasoningEnvelope(changed))
            JsonObject(block + ("data" to JsonPrimitive(envelope)))
        }
    }

    private fun nativeBlocks(wire: String): List<JsonObject> = wire.lineSequence()
        .filter { it.startsWith("data: ") }
        .map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "content_block_start" }
        .map { it.getValue("content_block").jsonObject }
        .filter { it["type"]?.jsonPrimitive?.content == "redacted_thinking" }
        .toList()
}

private val CROSS_SCRIPT_SOURCE = "await tools.Read({fixture:'" + "x".repeat(600) + "'});\n"

class CodeModeCrossScriptSourceTest {
    @ParameterizedTest
    @CsvSource("1,1,false", "1,1,true", "1,2,false", "1,2,true", "4,1,false", "4,1,true", "4,2,false", "4,2,true")
    @Timeout(BILLING_TEST_SECONDS)
    fun `earlier commentary stays outside a later live script with native-only callbacks`(
        preludeCalls: Int,
        liveSteps: Int,
        reminder: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = Fixture(tmp, preludeCalls)
        try {
            val history = fixture.begin()
            if (reminder) history += message("system", JsonPrimitive("synthetic context notification"))
            repeat(liveSteps) { at ->
                val next = async { send(fixture.client, fixture.url, history) }
                val delivered = withTimeoutOrNull(1_000) { fixture.runtime.delivered.receive() }
                assertEquals(0, fixture.ws.aborts.get(), "A commentary must not interrupt live B at step $at")
                assertTrue(delivered != null, "B must resume with its callback result at step $at")
                if (at + 1 < liveSteps) fixture.ws.nextNativeStatement() else fixture.ws.endSource()
                val step = withTimeout(TURN_BOUND_MS) { next.await() }
                history += fixture.returned(step)
                if (reminder) history += message("system", JsonPrimitive("synthetic context notification"))
            }
            val final = withTimeout(TURN_BOUND_MS) { send(fixture.client, fixture.url, history) }
            assertTrue(final.contains("fixture read"), final)
            assertCommentaryOnce(fixture.ws.requests.last())
        } finally {
            fixture.close()
        }
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a deliberately interrupted live exec replays the streamed prefix rather than an empty input`(
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = Fixture(tmp, 1)
        try {
            val history = fixture.begin()
            history += message("user", JsonPrimitive("stop this synthetic script"))
            val final = withTimeout(TURN_BOUND_MS) { send(fixture.client, fixture.url, history) }
            assertTrue(final.contains("fixture read"), final)
            assertEquals(1, fixture.ws.aborts.get(), "genuine steering must still interrupt B")
            val input = Json.parseToJsonElement(fixture.ws.requests.last()).jsonObject.getValue("input") as JsonArray
            val exec = input.map { it.jsonObject }.single {
                it["type"]?.jsonPrimitive?.content == "custom_tool_call" &&
                    it["call_id"]?.jsonPrimitive?.content == "source-call"
            }
            assertEquals(
                CROSS_SCRIPT_SOURCE + "await ",
                exec.getValue("input").jsonPrimitive.content,
                "an interrupted B must preserve the exec bytes already streamed and admitted",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `A commentary is replayed once after genuine interruption of B`(@TempDir tmp: Path) = runBlocking {
        val fixture = Fixture(tmp, 4)
        try {
            val history = fixture.begin()
            history += message("user", JsonPrimitive("stop this synthetic script"))
            val final = withTimeout(TURN_BOUND_MS) { send(fixture.client, fixture.url, history) }
            assertTrue(final.contains("fixture read"))
            assertCommentaryOnce(fixture.ws.requests.last())
        } finally {
            fixture.close()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    @Timeout(BILLING_TEST_SECONDS)
    fun `extra-content interruption logs one structural cause without private item content`(
        systemOnly: Boolean,
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = Fixture(tmp, 1)
        val privateText = "SYNTHETIC_PRIVATE_DO_NOT_LOG"
        try {
            val history = fixture.begin()
            if (systemOnly) history.removeAt(history.lastIndex)
            val role = if (systemOnly) "system" else "user"
            history += message(role, JsonPrimitive(privateText))
            val final = withTimeout(TURN_BOUND_MS) { send(fixture.client, fixture.url, history) }
            assertTrue(final.contains("fixture read"))
            assertEquals(1, fixture.ws.aborts.get(), "the chosen extra content must actually interrupt B")
            val lines = fixture.logs.filter { "[code-mode] interrupted" in it }
            assertEquals(1, lines.size, "each extra-content interruption needs one deciding structural log line")
            val line = lines.single()
            val kind = if (systemOnly) "SYSTEM" else "STEERING"
            assertTrue("extra=$kind" in line, line)
            assertTrue("baselineLogicalCount=4" in line && "boundary=4" in line, line)
            val item = if (systemOnly) "msg:developer@5" else "msg:user@6"
            assertTrue("unowned=[$item]" in line, line)
            assertTrue("answered=${!systemOnly}" in line, line)
            assertTrue(privateText !in line && PRELUDE_COMMENTARY !in line && "xxxx" !in line, line)
        } finally {
            fixture.close()
        }
    }

    private fun assertCommentaryOnce(bodyJson: String) {
        val input = Json.parseToJsonElement(bodyJson).jsonObject.getValue("input") as JsonArray
        val count = input.count { item ->
            (item as? JsonObject)?.get("content")?.let { PRELUDE_COMMENTARY in it.toString() } == true
        }
        assertEquals(1, count, "A commentary must not be reintroduced in B's tail")
    }

    private class Fixture(tmp: Path, private val preludeCalls: Int) {
        val upstream = BillingUpstream()
        val runtime = StatementGatewayRuntime()
        val logs = ConcurrentLinkedQueue<String>()
        val ws = BillingWsRunner(CROSS_SCRIPT_SOURCE, reasoning = true, preludeCalls = preludeCalls)
        private val bridge = CodexCodeModeBridge(
            CodeModeBridgeConfig(
                runtimes = { runtime },
                state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
                clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                log = LogSink { logs += it },
            ),
        )
        private val head = HeadServer(withWs(upstream.url, bridge, ws, replayReasoning = false), 0, headDeps(tmp))
        val client = HttpClient(CIO)
        val url: String get() = "http://127.0.0.1:${head.port}/v1/messages"

        suspend fun begin(): MutableList<JsonObject> {
            head.start()
            val opening = message("user", JsonPrimitive("read a synthetic prelude then start another script"))
            val history = mutableListOf(opening)
            val a = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertEquals(preludeCalls, toolUses(a).size, "A delivers its real client callback batch")
            assertSignedNotice(a, "Promise.all")
            history += returned(a)
            val b = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertEquals(1, toolUses(b).size, "B publishes its first native-only callback while its source is live")
            assertTrue(!b.contains(PRELUDE_COMMENTARY), "B must not emit A commentary")
            assertSignedNotice(b, "tools.Read")
            withTimeout(TURN_BOUND_MS) { runtime.delivered.receive() }
            history += returned(b)
            return history
        }

        fun returned(wire: String): List<JsonObject> = listOf(
            message("assistant", JsonArray(clientBlocks(wire))),
            message("user", JsonArray(toolUses(wire).map(::result))),
        )

        suspend fun close() {
            ws.endSource()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }

        private fun assertSignedNotice(wire: String, sourceMarker: String) {
            val blocks = clientBlocks(wire)
            assertTrue(blocks.none { it["type"]?.jsonPrimitive?.content == "redacted_thinking" })
            val notices = blocks.filter {
                it["type"]?.jsonPrimitive?.content == "thinking" &&
                    it["signature"]?.jsonPrimitive?.content == SpliceNotice.SIGNATURE
            }
            assertEquals(1, notices.size, "the client echoes the actual signed live-script notice")
            assertTrue(sourceMarker in notices.single().getValue("thinking").jsonPrimitive.content)
        }

        private fun clientBlocks(wire: String): List<JsonObject> {
            val blocks = linkedMapOf<Int, JsonObject>()
            val arguments = mutableMapOf<Int, StringBuilder>()
            wire.lineSequence().filter { it.startsWith("data: ") }.forEach { line ->
                val event = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
                val at = event["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                when (event["type"]?.jsonPrimitive?.content) {
                    "content_block_start" -> blocks[at] = event.getValue("content_block").jsonObject
                    "content_block_delta" -> replayDelta(at, event.getValue("delta").jsonObject, blocks, arguments)
                }
            }
            arguments.forEach { (at, json) ->
                blocks[at] = JsonObject(blocks.getValue(at) + ("input" to Json.parseToJsonElement(json.toString())))
            }
            return blocks.values.sortedBy { it["type"]?.jsonPrimitive?.content != "text" }
        }

        private fun replayDelta(
            at: Int,
            delta: JsonObject,
            blocks: MutableMap<Int, JsonObject>,
            arguments: MutableMap<Int, StringBuilder>,
        ) {
            val block = blocks[at] ?: return
            val field = when (delta["type"]?.jsonPrimitive?.content) {
                "text_delta" -> "text"
                "thinking_delta" -> "thinking"
                "signature_delta" -> "signature"
                else -> null
            }
            if (field != null) {
                val before = block[field]?.jsonPrimitive?.content.orEmpty()
                val text = before + delta.getValue(field).jsonPrimitive.content
                blocks[at] = JsonObject(block + (field to JsonPrimitive(text)))
            } else if (delta["type"]?.jsonPrimitive?.content == "input_json_delta") {
                arguments.getOrPut(at) { StringBuilder() }.append(delta.getValue("partial_json").jsonPrimitive.content)
            }
        }
    }
}

/** Real head rows and releases, with the two source-terminal settlement boundaries held deterministically. */
class CodeModeTerminalBillingTest {
    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a staged terminal consumed after lease removal cannot bill its held posting row again`(
        @TempDir tmp: Path,
        reporter: TestReporter,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true)
        val runtime = StatementGatewayRuntime()
        val bridge = billingBridge(tmp, runtime)
        val head = HeadServer(provider(upstream.url, bridge), 0, headDeps(tmp))
        val client = HttpClient(CIO)
        val staged = CountDownLatch(1)
        val settle = CountDownLatch(1)
        try {
            head.start()
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val history = mutableListOf(message("user", JsonPrimitive("read the synthetic fixture")))
            val first = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(first.contains("message_stop"), first)
            val round = billingRound(bridge)
            assertHeldBillingSource(round)
            val release = recordBillingRelease(round)
            stageBillingTerminal(round, upstream, staged, settle)
            history += message("assistant", JsonArray(toolUses(first)))
            history += message("user", JsonArray(toolUses(first).map(::result)))
            val replay = replayParkedBillingCallback(client, url, history, bridge, round)
            assertEquals(1, upstream.posts.get(), "local callbacks post no raw round")
            history += message("assistant", JsonArray(toolUses(replay)))
            history += message("user", JsonArray(toolUses(replay).map(::result)))
            val answer = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(answer.contains("message_stop"), answer)
            assertTrue(
                billingStateFlag(round, "consumed"),
                "the old callback must consume staged usage while the held posting row cannot settle",
            )
            settle.countDown()
            val settled = withTimeout(TURN_BOUND_MS) { release.await() }
            val reported = rows(tmp, 4).sortedBy { it.getValue("ts").jsonPrimitive.long }
            reportBillingRows(reporter, reported, settled)
            assertBillingRowsOnce(reporter, reported)
            assertNull(settled, "already-consumed usage must not bill the held row again")
        } finally {
            settle.countDown()
            head.stop()
            runtime.close()
            client.close()
            upstream.close()
        }
    }

    @Test
    @Timeout(BILLING_TEST_SECONDS)
    fun `a parsed successful terminal keeps its held row bill across head stop before finish`(
        @TempDir tmp: Path,
        reporter: TestReporter,
    ) = runBlocking {
        val upstream = BillingUpstream(held = true)
        val runtime = StatementGatewayRuntime()
        val bridge = billingBridge(tmp, runtime)
        val head = HeadServer(provider(upstream.url, bridge), 0, headDeps(tmp))
        val client = HttpClient(CIO)
        try {
            head.start()
            val history = listOf(message("user", JsonPrimitive("read the synthetic fixture")))
            val url = "http://127.0.0.1:${head.port}/v1/messages"
            val first = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
            assertTrue(first.contains("message_stop"), first)
            val round = billingRound(bridge)
            assertHeldBillingSource(round)
            val release = recordBillingRelease(round)
            synchronized(billingField(round, "lifecycle")) {
                upstream.endSource()
                awaitParsedBillingTerminal(round)
                assertNull(reportedBillingUsage(round), "finish must still be blocked before it records the terminal")
                round.javaClass.getDeclaredMethod("stop").apply { isAccessible = true }.invoke(round)
            }
            val settled = checkNotNull(withTimeout(TURN_BOUND_MS) { release.await() })
            val posting = rows(tmp, 1).single()
            reportBillingRows(reporter, listOf(posting), settled)
            assertEquals(SOURCE_INPUT, settled.inputTokens, "settlement keeps parsed input despite raw precedence")
            assertEquals(SOURCE_OUTPUT, settled.outputTokens)
            assertEquals(SOURCE_CACHED, settled.cachedTokens)
            assertEquals(0L, settled.cutRounds, "a successful terminal is not a cut")
            assertEquals(SOURCE_INPUT, posting.count(PerfKeys.IN_TOKENS), "parsed input must stay on its held row")
            assertEquals(SOURCE_OUTPUT, posting.count(PerfKeys.OUT_TOKENS))
            assertEquals(SOURCE_CACHED, posting.count(PerfKeys.CACHED_TOKENS))
            assertEquals(0L, posting.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L, "a successful terminal is not a cut")
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
            val round = billingRound(bridge)
            val reader = billingField(round, "finished") as kotlinx.coroutines.Deferred<*>
            assertTrue(reader.isActive, "restart must begin while source generation is still active")
            head.stop()
            val posting = rows(tmp, 1).single()
            assertHeadRestartBill(posting)
            assertTrue(reader.isCancelled)
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
            val round = billingRound(bridge)
            val billing = billingField(round, "billing")
            val owed = billing.javaClass.getDeclaredField("owed").apply { isAccessible = true }.get(billing)
            assertNull(owed, "the first admission has not parked or held a posting row")
            if (reported) {
                ws.endSource()
                val reader = billingField(round, "finished") as kotlinx.coroutines.Deferred<*>
                withTimeout(TURN_BOUND_MS) { reader.await() }
                assertEquals(SOURCE_INPUT, checkNotNull(reportedBillingUsage(round)).inputTokens)
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

/** A retained reader keeps its generation even when the reusable head accepts a new source. */
class CodeModeHeadGenerationBillingTest {
    @ParameterizedTest
    @ValueSource(strings = ["cancel", "stopClientStep", "lease", "cell-close"])
    @Timeout(BILLING_TEST_SECONDS)
    fun `a restarted head preserves old head ownership and new client or retirement ownership`(
        cancellation: String,
        @TempDir tmp: Path,
    ) = runBlocking {
        val fixture = HeadGenerationBillingFixture(tmp)
        try {
            fixture.head.start()
            val old = fixture.startSource("synthetic old generation")
            assertHeldBillingSource(old)
            fixture.nextGeneration()
            assertTrue(fixture.reader(old).isActive, "the old independent reader must survive the driver restart")
            val next = fixture.startSource("synthetic next generation", old)
            assertHeldBillingSource(next)
            val oldSignal = billingField(old, "headStop") as HeadStopSignal
            val nextSignal = billingField(next, "headStop") as HeadStopSignal
            assertNotSame(oldSignal, nextSignal, "the real head start must replace, never reset, its signal")
            assertTrue(oldSignal.isStopping, "starting another generation cannot reopen an old round")
            assertFalse(nextSignal.isStopping, "a new client source belongs to the new live generation")
            fixture.cut(old, cancellation)
            fixture.cut(next, cancellation)
            assertFalse(fixture.takeCut(old), "the surviving old source is head-owned")
            assertEquals(cancellation != "lease", fixture.takeCut(next), "lease retirement is not a client cut")
            assertFalse(fixture.takeCut(next), "a genuine new client cut is consumed once")
            assertTrue(fixture.reader(old).isCancelled)
            assertTrue(fixture.reader(next).isCancelled)
        } finally {
            fixture.close()
        }
    }
}

private class HeadGenerationBillingFixture(tmp: Path) {
    private val upstream = BillingUpstream()
    private val runtime = StatementGatewayRuntime()
    private val bridge = billingBridge(tmp, runtime)
    private var source = BillingWsRunner()
    private val delegate = provider(upstream.url, bridge)

    private val rotating = object : Provider by delegate {
        override val wsRunner: WsRoundRunner get() = source
    }
    val head = HeadServer(rotating, 0, headDeps(tmp))
    private val client = HttpClient(CIO)

    suspend fun startSource(text: String): Any = startSourceAfter(text) { true }

    suspend fun <P : Any> startSource(text: String, previous: P): Any = startSourceAfter(text) { it !== previous }

    private suspend fun startSourceAfter(text: String, isNew: (Any?) -> Boolean): Any {
        val url = "http://127.0.0.1:${head.port}/v1/messages"
        val answer = withTimeout(TURN_BOUND_MS) {
            send(client, url, listOf(message("user", JsonPrimitive(text))))
        }
        assertTrue(toolUses(answer).isNotEmpty(), "the source must park before restart: $answer")
        val streams = billingField(billingField(bridge, "driver"), "streams")
        return checkNotNull((billingField(streams, "rounds") as Map<*, *>).values.single(isNew))
    }

    fun nextGeneration() {
        // Exercise the real driver generation boundary without tearing down the retained source transport.
        // The separate first-admission control exercises the entire unchanged HeadServer.stop drain.
        val driver = billingField(billingField(head, "driver"), "oneDrive")
        invoke(driver, "stopActive")
        invoke(driver, "headStarted")
        source = BillingWsRunner()
    }

    fun <R : Any> reader(round: R): kotlinx.coroutines.Deferred<*> =
        billingField(round, "finished") as kotlinx.coroutines.Deferred<*>

    fun <R : Any> cut(round: R, cancellation: String) {
        when (cancellation) {
            "lease" -> {
                val record = billingField(billingField(round, "capture"), "record")
                invoke(billingField(record, "sourceEnd"), "ended")
            }
            "cell-close" -> {
                val record = billingField(billingField(round, "capture"), "record")
                val cells = billingField(billingField(bridge, "registry"), "cells") as Map<*, *>
                (checkNotNull(cells[billingField(record, "id")]) as CodeModeCell).close()
            }
            else -> invoke(round, cancellation)
        }
    }

    fun <R : Any> takeCut(round: R): Boolean = invoke(round, "takeCut") as Boolean

    private fun <O : Any> invoke(owner: O, method: String): Any? =
        owner.javaClass.getDeclaredMethod(method).apply { isAccessible = true }.invoke(owner)

    suspend fun close() {
        head.stop()
        bridge.onHeadStop()
        runtime.close()
        client.close()
        upstream.close()
    }
}

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

    override suspend fun start(source: String, tools: Set<String>, descriptions: Map<String, String>): CodeModeCell =
        error("the synthetic first step must use its streaming source")

    override suspend fun startStreaming(
        source: CodeModeSource,
        tools: Set<String>,
        descriptions: Map<String, String>,
    ): CodeModeCell {
        started.complete(Unit)
        reject.await()
        if (failure is CancellationException) currentCoroutineContext().cancel(failure)
        throw failure
    }

    override fun close() = Unit
}

private fun <R : Any> stageBillingTerminal(
    round: R,
    upstream: BillingUpstream,
    staged: CountDownLatch,
    settle: CountDownLatch,
) {
    round.javaClass.getDeclaredField("beforeSettle").apply { isAccessible = true }.set(
        round,
        Runnable {
            staged.countDown()
            check(settle.await(BILLING_TEST_SECONDS, TimeUnit.SECONDS)) { "the test never resumed settlement" }
        },
    )
    upstream.endSource()
    assertTrue(staged.await(BILLING_TEST_SECONDS, TimeUnit.SECONDS), "terminal never reached settlement")
    assertEquals(SOURCE_INPUT, checkNotNull(reportedBillingUsage(round)).inputTokens, "the parsed terminal")
    assertTrue(billingStateFlag(round, "complete"))
}

private suspend fun <R : Any> replayParkedBillingCallback(
    client: HttpClient,
    url: String,
    history: List<JsonObject>,
    bridge: CodexCodeModeBridge,
    round: R,
): String {
    val local = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
    assertTrue(toolUses(local).isNotEmpty(), local)
    loseBillingRecord(bridge, round)
    val replay = withTimeout(TURN_BOUND_MS) { send(client, url, history) }
    assertEquals(toolUses(local), toolUses(replay), "the parked callback replays recorded calls")
    assertFalse(billingStateFlag(round, "consumed"), "neither no-raw callback owns the source usage")
    return replay
}

private fun assertBillingRowsOnce(reporter: TestReporter, reported: List<JsonObject>) {
    val localRows = reported.drop(1).dropLast(1)
    reporter.publishEntry("no_raw_callback_inputs", localRows.map { it.count(PerfKeys.IN_TOKENS) }.toString())
    assertTrue(localRows.all { it.count(PerfKeys.IN_TOKENS) == 0L }, "local callbacks do not bill source input")
    assertTrue(localRows.all { it.count(PerfKeys.OUT_TOKENS) == 0L }, "local callbacks do not bill source output")
    assertEquals(SOURCE_INPUT, reported.first().count(PerfKeys.IN_TOKENS), "posting row keeps its raw source")
    assertEquals(ANSWER_INPUT, reported.last().count(PerfKeys.IN_TOKENS), "continuation keeps its own raw round")
    assertEquals(SOURCE_INPUT + ANSWER_INPUT, reported.sumOf { it.count(PerfKeys.IN_TOKENS) ?: 0L })
    assertEquals(SOURCE_OUTPUT + ANSWER_OUTPUT, reported.sumOf { it.count(PerfKeys.OUT_TOKENS) ?: 0L })
    assertEquals(SOURCE_CACHED, reported.sumOf { it.count(PerfKeys.CACHED_TOKENS) ?: 0L })
    assertEquals(0L, reported.sumOf { it.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L }, "a parsed terminal is not a cut")
}

private fun reportBillingRows(reporter: TestReporter, rows: List<JsonObject>, settlement: Usage?) {
    reporter.publishEntry(
        mapOf(
            "posting_row_input" to rows.first().count(PerfKeys.IN_TOKENS).toString(),
            "posting_row_output" to rows.first().count(PerfKeys.OUT_TOKENS).toString(),
            "continuation_row_input" to rows.drop(1).sumOf { it.count(PerfKeys.IN_TOKENS) ?: 0L }.toString(),
            "row_cut_source_rounds" to rows.sumOf { it.count(PerfKeys.CUT_SOURCE_ROUNDS) ?: 0L }.toString(),
            "settlement_input" to settlement?.inputTokens.toString(),
            "settlement_cut_rounds" to settlement?.cutRounds.toString(),
        ),
    )
}

private fun billingBridge(tmp: Path, runtime: StatementGatewayRuntime): CodexCodeModeBridge = CodexCodeModeBridge(
    CodeModeBridgeConfig(
        runtimes = { runtime },
        state = CodeModeStateLocation(tmp.resolve("records"), tmp.resolve("legacy.json")),
    ),
)

private fun <O : Any> billingField(owner: O, name: String): Any =
    checkNotNull(owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner))

private fun billingRound(bridge: CodexCodeModeBridge): Any {
    val streams = billingField(billingField(bridge, "driver"), "streams")
    return checkNotNull((billingField(streams, "rounds") as Map<*, *>).values.single())
}

private fun <R : Any> billingStateFlag(round: R, name: String): Boolean {
    val state = billingField(billingField(billingField(round, "capture"), "record"), "sourceState")
    return state.javaClass.getDeclaredField(name).apply { isAccessible = true }.getBoolean(state)
}

private fun <R : Any> loseBillingRecord(bridge: CodexCodeModeBridge, round: R) {
    val registry = billingField(bridge, "registry")
    val record = billingField(billingField(round, "capture"), "record")
    val retained = billingField(registry, "retainedCells")
    retained.javaClass.getDeclaredMethod("park", record.javaClass, String::class.java)
        .apply { isAccessible = true }.invoke(retained, record, "synthetic newer-program disposal")
    val streams = billingField(billingField(bridge, "driver"), "streams")
    assertTrue((billingField(streams, "rounds") as Map<*, *>).isEmpty(), "the lease must remove the old round")
}

/** Raw-post telemetry deliberately overrides these counters, so also observe billing's actual release. */
private fun <R : Any> recordBillingRelease(round: R): CompletableDeferred<Usage?> {
    val released = CompletableDeferred<Usage?>()
    val owed = billingField(billingField(round, "billing"), "owed")
    val field = owed.javaClass.getDeclaredField("release").apply { isAccessible = true }
    val original = field.get(owed) as RowRelease
    field.set(
        owed,
        RowRelease { usage ->
            released.complete(usage)
            original.release(usage)
        },
    )
    return released
}

private fun <R : Any> reportedBillingUsage(round: R): Usage? {
    val billing = billingField(round, "billing")
    return billing.javaClass.getDeclaredField("reported").apply { isAccessible = true }.get(billing) as? Usage
}

private fun <R : Any> assertHeldBillingSource(round: R) {
    val ended = round.javaClass.getDeclaredField("upstreamEnded").apply { isAccessible = true }
    assertFalse(ended.getBoolean(round), "the gated source must not have returned its terminal")
    val billing = billingField(round, "billing")
    val owed = billing.javaClass.getDeclaredField("owed").apply { isAccessible = true }.get(billing)
    assertTrue(owed != null, "the source posting row must already be held")
}

private fun <R : Any> awaitParsedBillingTerminal(round: R) {
    val ended = round.javaClass.getDeclaredField("upstreamEnded").apply { isAccessible = true }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BILLING_TEST_SECONDS)
    val pause = CountDownLatch(1)
    while (!ended.getBoolean(round) && System.nanoTime() < deadline) pause.await(GATE_POLL_MS, TimeUnit.MILLISECONDS)
    assertTrue(ended.getBoolean(round), "the post must return its parsed terminal before head stop")
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
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
        PerfKeys.OUT_TOKENS to SOURCE_OUTPUT,
    )
    val answered = mapOf(
        PerfKeys.IN_TOKENS to ANSWER_INPUT,
        PerfKeys.CACHED_TOKENS to 0L,
        PerfKeys.CACHE_WRITE_TOKENS to 0L,
        PerfKeys.OUT_TOKENS to ANSWER_OUTPUT,
    )
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

private fun assertWsReuse(ws: BillingWsRunner, upstream: BillingUpstream) {
    assertEquals(2, ws.cleanRounds.get(), "both WS terminals return healthy sockets to the pool")
    assertEquals(0, ws.aborts.get(), "the next client step continues the retained source")
    assertEquals(1, ws.connections.get(), "the clean source socket is pooled for the continuation")
    assertEquals(0, upstream.posts.get(), "the WS source is not reissued over SSE")
}

private fun provider(
    url: String,
    bridge: CodexCodeModeBridge,
    replayReasoning: Boolean = false,
): CodexProvider = CodexProvider(
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
    replayReasoning = replayReasoning,
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

    fun nextNativeStatement() {
        secondStatement = true
        val execIndex = if (reasoning) 1 else 0
        listener.emit(
            active,
            """{"type":"response.custom_tool_call_input.delta","output_index":$execIndex,
                "delta":${JsonPrimitive(source.removePrefix("await ") + "text (")}}""",
        )
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
