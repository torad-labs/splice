// NEW: V4-444 — a request splice turns away at the gate is a request the operator can see.
//
// A request refused because the gate was full, or because the head was stopping, used to leave a log line and nothing
// else: the client was told and no row existed, so the console's Requests list showed a gap exactly where splice itself
// declined work. The full-gate case is driven through a real head holding its one slot open on a blocked upstream,
// reading the perf file the Requests page reads; the stopping case is driven at the gate itself, since a stopped head
// has no listener to send a request to. Neither refusal began a turn, so neither may announce one starting or ending.
package splice.head.admission

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.perf.OutcomeTag
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.responses.ReasoningSettings
import splice.head.HeadServer
import splice.head.TestResponsesProvider
import splice.head.headDeps
import splice.head.headStores
import splice.head.perf.PerfStats
import splice.head.turn.SESSION_HEADER
import splice.upstream.ProviderLocations
import splice.upstream.ProviderName
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val AT_CAPACITY_STATUS = 529
private const val ENTRY_WAIT_SECONDS = 10L
private const val INTERNAL_ERROR = 500

// why: the second request must be inside the gate's queue before the third arrives; there is no signal for that.
private const val QUEUE_POLL_MS = 10L
private const val QUEUE_WAIT_SECONDS = 15

private object QuietAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.Bearer("tok", "acct")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

/** A real head with ONE slot and ONE queue place, over an upstream that holds the first turn open until [release]:
 *  a second request takes the queue place and a third meets a full gate. Rows go to a perf file this reads back. */
private class FullGateHead(tmp: Path) {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            entered.countDown()
            release.await(ENTRY_WAIT_SECONDS, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(INTERNAL_ERROR, -1)
            exchange.close()
        }
        start()
    }
    private val gate = InflightGate(maxInflight = { 1 }, maxQueued = { 1 })
    private val perfFile: Path = tmp.resolve("perf.jsonl")
    private val client = HttpClient(CIO) { defaultRequest { bearerAuth("test-inference-token") } }
    private val head = HeadServer(
        provider = TestResponsesProvider(
            tuning = ProviderTuning(
                name = ProviderName(key = "openai", label = "openai"),
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-openai--",
                    models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000)),
                    defaultContextWindow = 272_000,
                ),
                pinnedModel = "gpt-5.6-sol",
                auth = QuietAuth,
                locations = ProviderLocations(baseUrl = "http://127.0.0.1:${upstream.address.port}"),
                watchdog = WatchdogBudget(10.seconds, 10.seconds, 30.seconds),
            ),
            reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
        ),
        listenPort = 0,
        deps = headDeps(
            tmp = tmp,
            upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
            gate = gate,
            log = {},
        ).let { it.copy(stores = headStores(tmp).copy(perfStats = PerfStats(perfFile))) },
    )

    suspend fun start() = head.start()

    /** Returns once the gate holds the second request in its one queue place, so a third meets a full gate. */
    suspend fun awaitQueued() {
        withTimeout(QUEUE_WAIT_SECONDS.seconds) { while (gate.snapshot().queued != 1) delay(QUEUE_POLL_MS) }
    }

    suspend fun close() {
        release.countDown()
        head.stop()
        client.close()
        upstream.stop(0)
    }

    suspend fun turn(session: String? = null): HttpResponse = client.post("http://127.0.0.1:${head.port}/v1/messages") {
        header("Content-Type", "application/json")
        session?.let { header(SESSION_HEADER, it) }
        setBody(
            """{"model":"claude-openai--gpt-5.6-sol","stream":true,"max_tokens":64,
                "messages":[{"role":"user","content":"go"}]}""",
        )
    }

    /** The rows written so far. */
    fun rows(): List<JsonObject> {
        assertTrue(AsyncFileIo.drain())
        return Files.readString(perfFile).lineSequence().filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }.toList()
    }
}

class GateRefusalRowTest {
    /** The first turn holds the only slot; the second meets a full gate. The row it leaves has the refusal's own
     *  outcome and no model, since none was chosen before the body was read. */
    @Test
    fun `a request refused because the gate is full leaves a row of its own`(@TempDir tmp: Path) {
        runBlocking {
            val head = FullGateHead(tmp)
            head.start()
            try {
                val holding = async(Dispatchers.IO) { head.turn().bodyAsText() }
                // The wait blocks its thread, so the first turn runs on the IO pool where it can still make progress.
                assertTrue(head.entered.await(ENTRY_WAIT_SECONDS, TimeUnit.SECONDS), "the first turn holds the slot")
                val queued = async(Dispatchers.IO) { head.turn().bodyAsText() }
                head.awaitQueued()
                val refused = head.turn(session = "sess-refused-0001")
                assertEquals(AT_CAPACITY_STATUS, refused.status.value, "the client is told the gate is full")
                val row = head.rows().single()
                assertEquals(OutcomeTag.AT_CAPACITY.wire, row.text("outcome"), "splice's own refusal is a row: $row")
                assertFalse("model" in row, "no model was chosen, and none is invented: $row")
                assertEquals("sess-ref", row.text("session"), "attributed to the session that was turned away")
                head.release.countDown()
                holding.await()
                queued.await()
            } finally {
                head.close()
            }
        }
    }

    /** A request that arrives once the head is stopping is turned away at the front door with the restart's own
     *  outcome, so the operator reads one word for work cut by a restart and work refused during one. */
    @Test
    fun `a request that arrives while the head is stopping is recorded as a restart refusal`(@TempDir tmp: Path) =
        testApplication {
            val recorded = mutableListOf<Pair<OutcomeTag, String?>>()
            val window = AdmissionWindow().also { it.close() }
            val deps = headDeps(tmp = tmp, log = {})
            val gate = AdmissionGate(
                TestResponsesProvider(
                    tuning = ProviderTuning(
                        name = ProviderName(key = "openai", label = "openai"),
                        catalog = ModelCatalog(
                            discoveryPrefix = "claude-openai--",
                            models = listOf(ModelEntry("m", "M", contextWindow = 1_000)),
                            defaultContextWindow = 1_000,
                        ),
                        pinnedModel = "m",
                        auth = QuietAuth,
                        locations = ProviderLocations(baseUrl = "http://127.0.0.1"),
                        watchdog = WatchdogBudget(1.seconds, 1.seconds, 1.seconds),
                    ),
                    reasoning = ReasoningSettings(ReasoningDisplay.TEXT, false, "high", "detailed"),
                ),
                deps,
                window,
                AdmissionResponses(),
                refusals = GateRefusals { tag, session -> recorded += tag to session },
            )
            application { routing { post("/probe") { gate.acceptingOrRespond(call) } } }

            val answer = client.post("/probe") { header("x-claude-code-session-id", "sess-stop-0001") }

            assertEquals(AT_CAPACITY_STATUS, answer.status.value)
            assertEquals(listOf(OutcomeTag.RESTARTED to "sess-stop-0001"), recorded)
        }
}
