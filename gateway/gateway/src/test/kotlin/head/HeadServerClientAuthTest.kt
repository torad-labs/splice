// NEW (CH-7, campaign claude-head): the WALL around the client-auth front door.
//
// A client-auth head bypasses the mgmt-key check, because splice holds no credential for it and
// the caller's own auth is what rides upstream. That bypass is the one security decision in this
// campaign, so it is pinned from both sides at the HTTP level: the bypass works ONLY for a head
// that declares auth kind `client`, every other head still rejects a caller without the mgmt key,
// and no other head forwards a single inbound header.
//
// The upstream here RECORDS what it received — the only way to prove a header reached the wire,
// and reached it once. It is local to this test rather than an extension of the shared mock,
// which serves the codex-shaped dialects.
package head

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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
import splice.gateway.compact.CompactStats
import splice.gateway.compact.ShadowClassifier
import splice.gateway.head.HeadDeps
import splice.gateway.head.HeadServer
import splice.gateway.perf.PerfStats
import splice.gateway.usage.UsageStore
import splice.spi.InflightGate
import splice.spi.ProviderTuning
import splice.spi.UpstreamClient
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MGMT_KEY = "mgmt-key-for-this-test"

/** An Anthropic-shaped upstream that records every request's headers — APPEND-ONLY, so a test can
 *  pin "exactly one NEW request" with a size boundary instead of reading whatever request (possibly
 *  a previous test's) happened to arrive last. */
private class RecordingUpstream {
    val requests = CopyOnWriteArrayList<Map<String, List<String>>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.createContext("/v1/messages") { ex: HttpExchange ->
            ex.requestBody.readAllBytes()
            requests += ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
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

private class FakeApiKeyAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("splice-held-secret", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "fake")
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HeadServerClientAuthTest {

    private val upstream = RecordingUpstream()
    private val client = HttpClient(CIO)
    private lateinit var tmp: java.nio.file.Path

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-max--",
        models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
        defaultContextWindow = 200_000,
    )

    private val heads = mutableListOf<HeadServer>()

    private fun startHead(forwardClientAuth: Boolean): Int {
        val port = ServerSocket(0).use { it.localPort }
        val auth = if (forwardClientAuth) ClientAuthProvider("claude-max") else FakeApiKeyAuth()
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = "anthropic",
                label = "claude-max",
                catalog = catalog,
                pinnedModel = "claude-fable-5",
                auth = auth,
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-max"),
            staticHeaders = mapOf("anthropic-version" to "2023-06-01"),
        )
        val head = HeadServer(
            provider = provider,
            listenPort = port,
            deps = HeadDeps(
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
                inferenceToken = MGMT_KEY,
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                shadow = ShadowClassifier(log = {}),
                compactStats = CompactStats(tmp.resolve("compact-$port.json")),
                usageStore = UsageStore(tmp.resolve("usage-$port.json"), tmp.resolve("rl-$port.json")),
                perfStats = PerfStats(tmp.resolve("perf-$port.jsonl")),
                log = {},
                forwardClientAuth = forwardClientAuth,
            ),
        )
        runBlocking { head.start() }
        heads += head
        return port
    }

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("client-auth-test")
        upstream.start()
    }

    @AfterAll
    fun tearDown() {
        runBlocking { heads.forEach { it.stop() } }
        upstream.stop()
        client.close()
    }

    private fun turn(port: Int, headers: Map<String, String> = emptyMap()) = runBlocking {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            headers.forEach { (k, v) -> header(k, v) }
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-max--claude-fable-5","max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"hi"}],"stream":true}""",
            )
        }
        response.status to response.bodyAsText()
    }

    // ── the bypass ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a client-auth head serves a caller that presents no mgmt key`() {
        val port = startHead(forwardClientAuth = true)
        val (status, _) = turn(port, mapOf("Authorization" to "Bearer caller-own-token"))
        assertEquals(HttpStatusCode.OK, status)
    }

    @Test
    fun `a client-auth head forwards the caller's credential and wire knobs upstream, once`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(
            port,
            mapOf(
                "Authorization" to "Bearer caller-own-token",
                "anthropic-beta" to "oauth-2025-04-20",
                "anthropic-version" to "2099-01-01", // caller's choice must beat the configured default
            ),
        )
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        val sent = upstream.requests[before]
        assertEquals(listOf("Bearer caller-own-token"), sent["authorization"].orEmpty())
        assertEquals(listOf("oauth-2025-04-20"), sent["anthropic-beta"].orEmpty())
        // exactly one, and it is the caller's — not the provider's configured 2023-06-01
        assertEquals(listOf("2099-01-01"), sent["anthropic-version"].orEmpty())
        // splice holds no credential on this head, so nothing of its own is written
        assertFalse(sent["authorization"].orEmpty().any { it.contains("splice-held-secret") })
    }

    @Test
    fun `the configured default still rides when the caller sends none`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to "Bearer caller-own-token"))
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        assertEquals(listOf("2023-06-01"), upstream.requests[before]["anthropic-version"].orEmpty())
    }

    // ── the wall ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every other head still rejects a caller without the mgmt key`() {
        val port = startHead(forwardClientAuth = false)
        val (status, body) = turn(port, mapOf("Authorization" to "Bearer caller-own-token"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("invalid local gateway credentials"), body)
    }

    @Test
    fun `a non-client head forwards NO inbound header and uses its own credential`() {
        val port = startHead(forwardClientAuth = false)
        val before = upstream.requests.size
        val (status, _) = turn(
            port,
            mapOf(
                "Authorization" to "Bearer $MGMT_KEY",
                "anthropic-beta" to "smuggled-beta",
                "anthropic-version" to "2099-01-01",
            ),
        )
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        val sent = upstream.requests[before]
        // the head's OWN credential reached the upstream, and the caller's mgmt key did not
        assertEquals(listOf("splice-held-secret"), sent["x-api-key"].orEmpty())
        assertNull(sent["authorization"])
        assertTrue(sent["anthropic-beta"].orEmpty().isEmpty(), "a non-client head forwards nothing")
        assertEquals(listOf("2023-06-01"), sent["anthropic-version"].orEmpty())
    }
}
