// NEW: V4-345 — a perf row names the trace turn that recorded its request, so the console opens the
// request a person clicked (GET /api/heads/{head}/trace?turn=ID) rather than guessing it by time: five
// turns run at once and subagents share a session. Proven through the REAL HeadServer, on both paths
// that write a perf row: a turn that was served, and a turn refused before it was served (a blocking
// budget, one of the local refusals). A head that keeps no trace writes a row with no turn in it.
package splice.head.perf.v4345

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.budget.BudgetBlock
import splice.core.budget.HeadBudget
import splice.core.budget.NoHeadBudget
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.storage.ActivityDays
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadServer
import splice.head.headDeps
import splice.head.headStores
import splice.head.quotaFor
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

private const val TOKEN = "test-inference-token"

// why: the largest body a trace record keeps here; every body in this test is far smaller
private const val BODY_CAP = 1 shl 20

private const val REQUEST = """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
    """"messages":[{"role":"user","content":"hi"}],"stream":true}"""

private val ANSWER = listOf(
    """{"type":"message_start","message":{"usage":{"input_tokens":1}}}""",
    """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
    """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
    """{"type":"content_block_stop","index":0}""",
    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    """{"type":"message_stop"}""",
).joinToString("") { "data: $it\n\n" }

private class ApiKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("upstream-key", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

private object Refusing : HeadBudget {
    override fun admit(): BudgetBlock = BudgetBlock("the day's budget is spent", "spent=1 limit=1")
    override fun spent(atMs: Long, model: String, counters: Map<String, Long>) = Unit
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfRowTraceTurnTest {
    private val upstream: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val heads = mutableListOf<HeadServer>()
    private lateinit var tmp: Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-splice--",
        models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
        defaultContextWindow = 200_000,
    )

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        tmp = tempDir
        upstream.createContext("/v1/messages") { ex: HttpExchange ->
            ex.requestBody.readAllBytes()
            val body = ANSWER.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(HttpStatusCode.OK.value, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        upstream.start()
    }

    @AfterAll
    fun tearDown() {
        runBlocking { heads.forEach { it.stop() } }
        upstream.stop(0)
        client.close()
    }

    /** One head's files: its perf rows and, when it keeps one, its trace. */
    private inner class Head(val server: HeadServer, val perf: Path, val trace: Path)

    private fun start(traced: Boolean, budget: HeadBudget = NoHeadBudget): Head {
        val id = heads.size
        val traceDir = tmp.resolve("trace-$id")
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = "anthropic",
                label = "claude-splice",
                catalog = catalog,
                pinnedModel = "claude-fable-5",
                auth = ApiKeyAuth(),
                baseUrl = "http://127.0.0.1:${upstream.address.port}",
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
        )
        val days = ActivityDays(traceDir, "anthropic", 7, ownerOnly = true)
        val trace = if (traced) splice.head.syntheticTraceStore(days, "anthropic", BODY_CAP) else null
        val server = HeadServer(
            provider = provider,
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                quota = quotaFor(null, null, budget = budget),
            ).copy(stores = headStores(tmp, suffix = "-$id", trace = trace)),
        )
        runBlocking { server.start() }
        heads += server
        return Head(server, tmp.resolve("perf-$id.jsonl"), traceDir)
    }

    private fun turn(head: Head): HttpStatusCode = runBlocking {
        client.post("http://127.0.0.1:${head.server.port}/v1/messages") {
            header("Authorization", "Bearer $TOKEN")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "sess-v4345")
            setBody(REQUEST)
        }.status
    }

    private fun lines(file: Path): List<JsonObject> =
        Files.readAllLines(file).map { json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    /** The traced head's one perf row, and the id its one turn record carries. */
    private fun written(head: Head): Pair<JsonObject, String> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        val row = lines(head.perf).single()
        val days = Files.list(head.trace).use { it.toList() }.filter { it.toString().endsWith(".jsonl") }
        val turn = days.flatMap(::lines).single { it.text("kind") == "turn" }
        return row to checkNotNull(turn.text("turn")) { "the turn record carries its id" }
    }

    @Test
    fun `an untraced turn's perf row names the response the client saw and its full session`() {
        val head = start(traced = false)
        val reply = runBlocking {
            val response = client.post("http://127.0.0.1:${head.server.port}/v1/messages") {
                header("Authorization", "Bearer $TOKEN")
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", "sess-v4345")
                setBody(REQUEST)
            }
            response.status to response.bodyAsText()
        }
        assertEquals(HttpStatusCode.OK, reply.first)
        val opener = reply.second.lineSequence().first { it.startsWith("data:") && it.contains("message_start") }
        val message = json.parseToJsonElement(opener.removePrefix("data:").trim()).jsonObject
            .getValue("message").jsonObject
        val responseId = checkNotNull(message["id"]?.jsonPrimitive?.content)
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        val row = lines(head.perf).single()
        assertEquals(responseId, row.text("response_message_id"), "the exact id Claude Code recorded")
        assertEquals("sess-v4345", row.text("session_id"), "the lookup needs the full session id")
        assertEquals("1", row.text("attempts"), "upstream attempts are measured even without trace")
    }

    @Test
    fun `a served turn's perf row names the trace turn that recorded it`() {
        val head = start(traced = true)

        assertEquals(HttpStatusCode.OK, turn(head))

        val (row, traced) = written(head)
        assertEquals("ok", row.text("outcome"))
        assertEquals(traced, row.text("turn"), "the row names its trace turn")
    }

    @Test
    fun `a turn refused before it was served names its trace turn too`() {
        val head = start(traced = true, budget = Refusing)

        assertEquals(HttpStatusCode.Forbidden, turn(head))

        val (row, traced) = written(head)
        assertEquals("error:budget-blocked", row.text("outcome"))
        assertEquals("0", row.text("attempts"), "a local refusal made no upstream send")
        assertEquals(traced, row.text("turn"), "the refusal's row names its trace turn")
    }

    @Test
    fun `a head that keeps no trace writes a row with no turn`() {
        val head = start(traced = false)

        assertEquals(HttpStatusCode.OK, turn(head))

        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        assertNull(lines(head.perf).single()["turn"], "no trace, no turn")
    }
}
