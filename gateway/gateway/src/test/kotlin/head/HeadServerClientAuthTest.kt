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

    @Volatile var stopReason = "end_turn"
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
                append("\"delta\":{\"stop_reason\":\"$stopReason\"},\"usage\":{\"output_tokens\":1}}\n\n")
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
        discoveryPrefix = "claude-splice--",
        models = listOf(ModelEntry("claude-fable-5", "Claude Fable 5", contextWindow = 200_000)),
        defaultContextWindow = 200_000,
    )

    private val heads = mutableListOf<HeadServer>()

    /** The default pairs the flag with the credential state the daemon derives it FROM, which is
     *  also why this file only ever built the two agreeing cells — the `auth` override is what lets
     *  the flag and the credential be chosen independently. */
    private fun defaultAuthFor(forwardClientAuth: Boolean): RefreshableAuthProvider =
        if (forwardClientAuth) ClientAuthProvider("claude-splice") else FakeApiKeyAuth()

    private fun startHead(
        forwardClientAuth: Boolean,
        auth: RefreshableAuthProvider = defaultAuthFor(forwardClientAuth),
    ): Int {
        val port = ServerSocket(0).use { it.localPort }
        val provider = PassthroughProvider(
            tuning = ProviderTuning(
                key = "anthropic",
                label = "claude-splice",
                catalog = catalog,
                pinnedModel = "claude-fable-5",
                auth = auth,
                baseUrl = upstream.baseUrl,
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            quirks = PassthroughQuirks(providerTag = "claude-splice"),
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
                """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
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

    @Test
    fun `a passthrough context-window stop reaches Claude Code's compaction trigger`() {
        val port = startHead(forwardClientAuth = true)
        upstream.stopReason = "model_context_window_exceeded"
        try {
            val (status, body) = turn(port, mapOf("Authorization" to "Bearer caller-own-token"))
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(body.contains("event: error"), body)
            assertTrue(body.contains("invalid_request_error"), body)
            assertTrue(body.contains("prompt is too long"), body)
            assertFalse(body.contains("event: message_stop"), body)
        } finally {
            upstream.stopReason = "end_turn"
        }
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

    // ── the second cell that was never built (DR-30) ──────────────────────────────────────────
    //
    // Bypass ON and the caller presents splice's OWN mgmt key. Every case above assumes the caller
    // holds either a real vendor credential or nothing; none asks what happens when the credential
    // it forwards is the local one. The launcher makes that reachable: LaunchService plants
    // ANTHROPIC_AUTH_TOKEN=<mgmt key> for every NON-native head, bin/splice-launch execs `env`
    // WITHOUT -i so the parent environment survives, and a native head's unset list is empty by
    // design — so a native head launched from inside another head's session inherits that bearer
    // and hands it straight to this seam. Forwarding it means splice's local key reaches the vendor
    // (the SAFETY shape commit 2ba8780 fixed in the E2E harness and not here) and the user's real
    // credential is never used at all.

    @Test
    fun `a client-auth head refuses the caller's own splice management key instead of forwarding it`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, body) = turn(port, mapOf("Authorization" to "Bearer $MGMT_KEY"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("management key"), body)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `the refusal covers the x-api-key spelling of the same key`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("x-api-key" to MGMT_KEY))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    // DR-30 redo (codex adversarial verdict, 2026-08-30): the first guard checked only the ONE
    // credential presentedCredential would pick — Authorization's bearer first — while the
    // forwarding allowlist sends BOTH headers. A caller whose bearer is its own token could
    // therefore still ride the mgmt key upstream in x-api-key; and a schemeless
    // `Authorization: <key>` parsed as no bearer at all yet the raw value is forwarded verbatim.
    // Every forwardable spelling is now checked independently; both arms were red on the first fix.

    @Test
    fun `mixed headers cannot smuggle the key - own bearer plus mgmt x-api-key is refused`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, body) = turn(
            port,
            mapOf("Authorization" to "Bearer caller-own-token", "x-api-key" to MGMT_KEY),
        )
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("management key"), body)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `a schemeless Authorization spelling of the key is refused, not forwarded verbatim`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to MGMT_KEY))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    // Second DR-30 redo: raw string equality missed every OTHER scheme — `Basic <key>` is neither
    // byte-equal to the key, nor a Bearer, nor x-api-key, yet the raw header (key included)
    // forwards verbatim. The check is token-wise now: the key may not appear as any
    // whitespace-separated token of the Authorization value.
    @Test
    fun `a Basic-scheme spelling of the key is refused, not forwarded verbatim`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to "Basic $MGMT_KEY"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    // ── the cell that was never built ─────────────────────────────────────────────────────────
    //
    // Bypass ON while splice STILL HOLDS a credential. Every case above ties the flag to the auth
    // provider, so this DISAGREEING pairing — the one the daemon could actually reach while it
    // derived the flag from the TOML string instead of from `wired.auth` — is the cell this wall
    // never constructed, which is exactly why the defect survived it.
    //
    // What it pins is what the bypass COSTS when it is not backed by an empty credential: the door
    // opens for a caller with no mgmt key AND splice's own secret is what reaches the vendor. The
    // daemon-side wall that this is unreachable in practice lives in :app
    // (ClientAuthDerivationTest); this one states why it must stay unreachable.
    @Test
    fun `bypass alongside a splice-held credential opens the door and spends splice's own key`() {
        val port = startHead(forwardClientAuth = true, auth = FakeApiKeyAuth())
        val before = upstream.requests.size
        val (status, _) = turn(port) // no Authorization header at all
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        assertEquals(listOf("splice-held-secret"), upstream.requests[before]["x-api-key"].orEmpty())
    }
}
