// NEW: V4-174 — the full trace, proven through the REAL HeadServer against a recording upstream:
// the attempt record's body is compared BYTE FOR BYTE with what the upstream received, the turn
// record's client body with what the client posted, and what was streamed back with the frames the
// client actually read. The two credentials in play — the client's bearer on the way in, the
// head's API key on the way out — must appear NOWHERE in the file, which is the claim "headers
// redacted" actually makes. A head built without a trace writes nothing at all: no directory.
package splice.head

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
import splice.core.storage.ActivityDays
import splice.core.turn.WatchdogBudget
import splice.core.util.AsyncFileIo
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.wire.TraceStore
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MGMT_KEY = "mgmt-key-for-the-trace-test"
private const val UPSTREAM_SECRET = "splice-held-upstream-secret"

/** An Anthropic-shaped upstream that records every request BODY, append-only. */
private class TraceRecordingUpstream {
    val bodies = CopyOnWriteArrayList<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.createContext("/v1/messages") { ex: HttpExchange ->
            bodies += ex.requestBody.readAllBytes().decodeToString()
            val body = SSE_ANSWER.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("x-request-id", "upstream-req-1")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

private val SSE_ANSWER = buildString {
    append("event: message_start\ndata: {\"type\":\"message_start\",")
    append("\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n")
    append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,")
    append("\"content_block\":{\"type\":\"text\"}}\n\n")
    append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,")
    append("\"delta\":{\"type\":\"text_delta\",\"text\":\"traced\"}}\n\n")
    append("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
    append("event: message_delta\ndata: {\"type\":\"message_delta\",")
    append("\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n")
    append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
}

private class TraceTestApiKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey(UPSTREAM_SECRET, "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadTraceTest {

    private val upstream = TraceRecordingUpstream()
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tmp: Path
    private val heads = mutableListOf<HeadServer>()

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-splice--",
        models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
        defaultContextWindow = 200_000,
    )

    private fun traceDir(port: Int): Path = tmp.resolve("trace-$port")

    private fun startHead(traced: Boolean): Int {
        val port = ServerSocket(0).use { it.localPort }
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = "anthropic",
                label = "claude-splice",
                catalog = catalog,
                pinnedModel = "claude-fable-5",
                auth = TraceTestApiKeyAuth(),
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
        )
        val trace = if (traced) {
            TraceStore(ActivityDays(traceDir(port), "anthropic", 7, ownerOnly = true), "anthropic", 1 shl 20)
        } else {
            null
        }
        val head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                log = {},
            ).copy(
                inferenceToken = MGMT_KEY,
                stores = headStores(tmp, suffix = "-$port", trace = trace),
            ),
        )
        runBlocking { head.start() }
        heads += head
        return port
    }

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("head-trace-test")
        upstream.start()
    }

    @AfterAll
    fun tearDown() {
        runBlocking { heads.forEach { it.stop() } }
        upstream.stop()
        client.close()
    }

    private fun turn(port: Int, body: String, session: String = "sess-trace-1"): Pair<HttpStatusCode, String> =
        runBlocking {
            val response = client.post("http://127.0.0.1:$port/v1/messages") {
                header("Authorization", "Bearer $MGMT_KEY")
                header("Content-Type", "application/json")
                header("x-claude-code-session-id", session)
                setBody(body)
            }
            response.status to response.bodyAsText()
        }

    private fun records(port: Int): List<JsonObject> {
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        val files = Files.list(traceDir(port)).use { it.toList() }.filter { it.toString().endsWith(".jsonl") }
        return files.single().let(Files::readAllLines).map { json.parseToJsonElement(it).jsonObject }
    }

    private fun JsonObject.at(vararg path: String): String? =
        path.dropLast(1).fold(this) { node, key -> node.getValue(key).jsonObject }[path.last()]?.jsonPrimitive?.content

    @Test
    fun `a traced turn - the upstream body byte for byte, the client body, the frames, no credential`() {
        val port = startHead(traced = true)
        val before = upstream.bodies.size
        val posted = """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
            """"system":"house rules","messages":[{"role":"user","content":"trace me"}],"stream":true}"""

        val (status, streamed) = turn(port, posted)

        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.bodies.size, "one turn, one upstream request")
        val (attempt, turn) = records(port)
        assertEquals("attempt", attempt.at("kind"))
        assertEquals(upstream.bodies[before], attempt.at("request", "body"), "what the upstream received")
        assertEquals("[redacted]", attempt.at("request", "headers", "x-api-key"))
        assertEquals("200", attempt.at("response", "status"))
        assertEquals("upstream-req-1", attempt.at("response", "headers", "x-request-id"))
        assertEquals(SSE_ANSWER, attempt.at("response", "text"), "the raw response text, whole")
        assertEquals("turn", turn.at("kind"))
        assertEquals(attempt.at("turn"), turn.at("turn"), "one id across the turn's records")
        assertEquals("sess-trace-1", turn.at("session"))
        assertEquals("claude-fable-5", turn.at("model"))
        assertEquals(posted, turn.at("client", "body"), "what the client posted")
        assertEquals("POST", turn.at("client", "method"))
        assertEquals("/v1/messages", turn.at("client", "path"))
        assertEquals("[redacted]", turn.at("client", "headers", "Authorization"))
        assertEquals(streamed, turn.at("answer", "body"), "what the client read, frame for frame")
        assertEquals("ok", turn.at("outcome"))
        assertEquals("1", turn.at("rounds"))
        assertEquals("1", turn.at("attempts"))
        assertTrue(turn.getValue("perf").jsonObject.getValue("marks").jsonObject.containsKey("total"))

        val dayFile = Files.list(traceDir(port)).use { it.toList() }.single { "$it".endsWith(".jsonl") }
        val file = Files.readString(dayFile)
        assertFalse(file.contains(MGMT_KEY), "the client's bearer is not on disk")
        assertFalse(file.contains(UPSTREAM_SECRET), "the head's API key is not on disk")
    }

    @Test
    fun `a head without a trace writes nothing - no directory exists`() {
        val port = startHead(traced = false)

        val body = """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
            """"messages":[{"role":"user","content":"untraced"}],"stream":true}"""
        assertEquals(HttpStatusCode.OK, turn(port, body).first)

        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        assertFalse(Files.exists(traceDir(port)), "no trace directory for a head that did not opt in")
    }
}
