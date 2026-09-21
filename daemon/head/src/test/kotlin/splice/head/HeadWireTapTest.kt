// NEW: V4-173 — the upstream wire tap, proven at the HTTP level through the REAL HeadServer: the
// body GET /wire hands back is compared BYTE FOR BYTE with the body the upstream received, because
// "shows what we sent" is a claim about the wire, not about a field the head happened to keep. The
// upstream here records BODIES (HeadServerClientAuthTest's sibling records headers), append-only,
// so a cell pins "exactly these new requests" with a size boundary.
//
// The opt-in is pinned from both sides: a head built without a tap answers 404 naming the knob (an
// empty list would read as "nothing was sent"), and the route takes the management key alone — on
// a client-auth head too, whose turn policy is "anyone with their own credential" and whose bodies
// are exactly the ones a stranger's credential must not open.
package splice.head

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.dialect.passthrough.PassthroughProvider
import splice.dialect.passthrough.PassthroughQuirks
import splice.head.wire.WireTap
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MGMT_KEY = "mgmt-key-for-the-wire-test"

/** An Anthropic-shaped upstream that records every request BODY, append-only. */
private class BodyRecordingUpstream {
    val bodies = CopyOnWriteArrayList<String>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.createContext("/v1/messages") { ex: HttpExchange ->
            bodies += ex.requestBody.readAllBytes().decodeToString()
            val body = buildString {
                append("event: message_start\ndata: {\"type\":\"message_start\",")
                append("\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n")
                append("event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,")
                append("\"content_block\":{\"type\":\"text\"}}\n\n")
                append("event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,")
                append("\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n")
                append("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
                append("event: message_delta\ndata: {\"type\":\"message_delta\",")
                append("\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n")
                append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
            }.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() = server.stop(0)
}

private class WireTestApiKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("splice-held-secret", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadWireTapTest {

    private val upstream = BodyRecordingUpstream()
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var tmp: java.nio.file.Path
    private val heads = mutableListOf<HeadServer>()

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-splice--",
        models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
        defaultContextWindow = 200_000,
    )

    private fun startHead(wireTap: WireTap?, forwardClientAuth: Boolean = false): Int {
        val port = ServerSocket(0).use { it.localPort }
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = "anthropic",
                label = "claude-splice",
                catalog = catalog,
                pinnedModel = "claude-fable-5",
                auth = if (forwardClientAuth) ClientAuthProvider("claude-splice") else WireTestApiKeyAuth(),
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
        )
        val head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                log = {},
                policy = HeadDeps.HeadPolicy(forwardClientAuth = forwardClientAuth),
            ).copy(
                inferenceToken = MGMT_KEY,
                stores = headStores(tmp, suffix = "-$port", wireTap = wireTap),
            ),
        )
        runBlocking { head.start() }
        heads += head
        return port
    }

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("wire-tap-test")
        upstream.start()
    }

    @AfterAll
    fun tearDown() {
        runBlocking { heads.forEach { it.stop() } }
        upstream.stop()
        client.close()
    }

    private fun turn(port: Int, text: String, bearer: String = MGMT_KEY) = runBlocking {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Authorization", "Bearer $bearer")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
                    """"system":"house rules","messages":[{"role":"user","content":"$text"}],"stream":true}""",
            )
        }
        response.status
    }

    private fun wire(port: Int, bearer: String? = MGMT_KEY, last: Int? = null) = runBlocking {
        val response = client.get("http://127.0.0.1:$port/wire" + (last?.let { "?last=$it" } ?: "")) {
            bearer?.let { header("Authorization", "Bearer $it") }
        }
        response.status to response.bodyAsText()
    }

    private fun bodiesOf(payload: String): List<String> =
        json.parseToJsonElement(payload).jsonObject.getValue("records").jsonArray
            .map { it.jsonObject.getValue("body").jsonPrimitive.content }

    @Test
    fun `the tap holds exactly the bytes the upstream received`() {
        val port = startHead(WireTap(keep = 4))
        val before = upstream.bodies.size
        assertEquals(HttpStatusCode.OK, turn(port, "first"))
        assertEquals(before + 1, upstream.bodies.size, "one turn, one upstream request")

        val (status, payload) = wire(port)

        assertEquals(HttpStatusCode.OK, status, payload)
        assertEquals(listOf(upstream.bodies[before]), bodiesOf(payload))
        val record = json.parseToJsonElement(payload).jsonObject.getValue("records").jsonArray.single().jsonObject
        assertEquals("claude-fable-5", record.getValue("model").jsonPrimitive.content)
        assertEquals("false", record.getValue("compact").jsonPrimitive.content)
        assertTrue(payload.contains("\"key\":\"anthropic\""), payload)
    }

    @Test
    fun `the ring keeps the last N bodies, oldest first, and --last narrows it`() {
        val port = startHead(WireTap(keep = 2))
        val before = upstream.bodies.size
        listOf("one", "two", "three").forEach { assertEquals(HttpStatusCode.OK, turn(port, it)) }
        assertEquals(before + 3, upstream.bodies.size)

        val (_, all) = wire(port)
        val (_, last) = wire(port, last = 1)

        assertEquals(upstream.bodies.subList(before + 1, before + 3), bodiesOf(all), "the oldest of three is gone")
        assertEquals(listOf(upstream.bodies[before + 2]), bodiesOf(last))
    }

    @Test
    fun `a head without the tap answers 404 naming the knob, never an empty list`() {
        val port = startHead(wireTap = null)
        assertEquals(HttpStatusCode.OK, turn(port, "unseen"))

        val (status, body) = wire(port)

        assertEquals(HttpStatusCode.NotFound, status, body)
        assertTrue(body.contains("[heads.anthropic.overrides] wireTap"), body)
    }

    @Test
    fun `the wire route takes the management key only, on a client-auth head too`() {
        val port = startHead(WireTap(keep = 1), forwardClientAuth = true)
        assertEquals(HttpStatusCode.OK, turn(port, "theirs", bearer = "caller-own-token"), "the turn path is theirs")

        assertEquals(HttpStatusCode.Unauthorized, wire(port, bearer = null).first, "no credential")
        assertEquals(HttpStatusCode.Unauthorized, wire(port, bearer = "caller-own-token").first, "a client credential")
        assertEquals(HttpStatusCode.OK, wire(port, bearer = MGMT_KEY).first, "the management key")
    }
}
