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
import splice.core.util.LogSink
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MGMT_KEY = "mgmt-key-for-this-test"

/** The v0.4.0 turn key: what a launched client presents, split from [MGMT_KEY]. */
private const val TURN_KEY = "turn-key-for-this-test"

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
        log: LogSink = { },
    ): Int {
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
            // 0: the head binds an OS-assigned port and reports it after start, so no leased port
            // can be taken between a lease and the bind.
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(firstByteTimeoutMs = 5_000, totalTimeoutMs = 30_000, maxRetries = 1),
                gate = InflightGate(maxInflight = { 4 }, maxQueued = { 4 }),
                log = log,
                policy = HeadDeps.HeadPolicy(forwardClientAuth = forwardClientAuth),
            ).copy(
                // This rig carries its OWN bearers and its own store files, keyed by the head's index
                // (the port is not known until the bind) so two heads in one test never share a
                // usage file. Two keys, as production wires them: the turn key a launched client
                // holds, and the management key.
                inferenceToken = TURN_KEY,
                operatorToken = MGMT_KEY,
                stores = headStores(tmp, suffix = "-${heads.size}"),
            ),
        )
        runBlocking { head.start() }
        heads += head
        return head.port
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

    private fun turn(port: Int, headers: Map<String, String> = emptyMap()) =
        turnWithLines(port, headers.map { (k, v) -> k to v })

    /** A turn written straight onto the socket, so the request reaches the head with the EXACT
     *  header lines given — repeats included.
     *
     *  The Ktor client cannot express this: `header(name, v)` twice arrives at the server as ONE
     *  comma-joined line, which would make a repeated-header test pass no matter what the server
     *  does with repeats. Raw bytes are the only way this assertion can fail. */
    private fun rawTurn(port: Int, headers: List<Pair<String, String>>, host: String = "127.0.0.1:$port"): String {
        val body = """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
            """"messages":[{"role":"user","content":"hi"}],"stream":true}"""
        val request = buildString {
            append("POST /v1/messages HTTP/1.1\r\n")
            append("Host: $host\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.toByteArray().size}\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Connection: close\r\n\r\n")
            append(body)
        }
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            socket.getInputStream().readBytes().decodeToString()
        }
    }

    /** A turn whose headers are a LIST of lines, so the same name can appear more than once —
     *  the shape Claude Code actually sends its beta flags in, which a Map cannot express. */
    private fun turnWithLines(port: Int, headers: List<Pair<String, String>>) = runBlocking {
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
    fun `repeated anthropic-beta lines ALL ride, rejoined - not just the first`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size

        val response = rawTurn(
            port,
            listOf(
                "Authorization" to "Bearer caller-own-token",
                "anthropic-beta" to "oauth-2025-04-20",
                "anthropic-beta" to "fine-grained-tool-streaming-2025-05-14",
                "anthropic-beta" to "context-1m-2025-08-07",
            ),
        )

        assertTrue(response.startsWith("HTTP/1.1 200"), response.take(120))
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        // RFC 9110 5.3: repeated list-valued field lines are ONE value. Reading the field with
        // `headers[name]` returned only the FIRST line, so a caller asking for three betas told the
        // vendor about one. Every flag the caller chose must arrive, however it was split.
        val flags = upstream.requests[before]["anthropic-beta"].orEmpty()
            .flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(
            setOf("oauth-2025-04-20", "fine-grained-tool-streaming-2025-05-14", "context-1m-2025-08-07"),
            flags,
        )
    }

    @Test
    fun `a repeated SINGLETON header keeps the first line - a credential is never rejoined`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size

        val response = rawTurn(
            port,
            listOf(
                "Authorization" to "Bearer caller-own-token",
                "anthropic-version" to "2099-01-01",
                "anthropic-version" to "1999-01-01",
            ),
        )

        assertTrue(response.startsWith("HTTP/1.1 200"), response.take(120))
        // Joining a singleton field would forge a value the caller never sent as one value, so the
        // first line wins. anthropic-version is the safe singleton to prove this on: the head's own
        // admission path reads Authorization, and a repeat there is a different question.
        assertEquals(listOf("2099-01-01"), upstream.requests[before]["anthropic-version"].orEmpty())
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
    // ANTHROPIC_AUTH_TOKEN=<mgmt key> for every NON-native head, app/src/main/dist/bin/splice-launch execs `env`
    // WITHOUT -i so the parent environment survives, and a native head's unset list is empty by
    // design — so a native head launched from inside another head's session inherits that bearer
    // and hands it straight to this seam. Forwarding it means splice's local key reaches the vendor
    // (the SAFETY shape commit 2ba8780 fixed in the E2E harness and not here) and the user's real
    // credential is never used at all.

    // ── the v0.4.0 key split ──────────────────────────────────────────────────────────────────
    //
    // A launched client now holds the TURN key, and the management key is no longer in any
    // session's environment. The management key must still run a turn (a session launched before the
    // split holds it until relaunched), and NEITHER key may ever be forwarded to a vendor.

    @Test
    fun `a gateway head runs a turn on the turn key and, for sessions launched before the split, the mgmt key`() {
        val port = startHead(forwardClientAuth = false)
        assertEquals(HttpStatusCode.OK, turn(port, mapOf("Authorization" to "Bearer $TURN_KEY")).first, "the turn key")
        assertEquals(HttpStatusCode.OK, turn(port, mapOf("Authorization" to "Bearer $MGMT_KEY")).first, "the mgmt key")
    }

    // v0.4.0: DNS rebinding. A client-auth head's turn door is open to any caller holding its own
    // credential, and a page in the operator's browser that rebinds attacker.example to 127.0.0.1 is
    // such a caller. Its requests name attacker.example in Host, the one thing rebinding cannot
    // change, so the head refuses them before routing and nothing reaches the vendor.
    @Test
    fun `a request naming a foreign Host is refused before any route runs, on the open door too`() {
        val logLines = CopyOnWriteArrayList<String>()
        val port = startHead(forwardClientAuth = true, log = { logLines += it })
        val before = upstream.requests.size
        val credential = listOf("Authorization" to "Bearer caller-own-token")
        val refused = rawTurn(port, credential, host = "attacker.example:$port")
        assertTrue(refused.startsWith("HTTP/1.1 403"), refused.lineSequence().first())
        assertEquals(before, upstream.requests.size, "a rebinding page's turn never reaches the vendor")
        val served = rawTurn(port, credential, host = "localhost:$port")
        assertTrue(served.startsWith("HTTP/1.1 200"), served.lineSequence().first())
        // v0.4.0 review: and the refusal is SAID in the head's log, which a rebinding page cannot read.
        val said = logLines.filter { it.startsWith("[security]") }
        assertEquals(1, said.size, logLines.toString())
        assertTrue(said.single().contains("'attacker.example:$port'"), said.single())
    }

    @Test
    fun `a client-auth head refuses the turn key too - neither splice key is an upstream credential`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to "Bearer $TURN_KEY"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        val (apiKeyStatus, _) = turn(port, mapOf("x-api-key" to TURN_KEY))
        assertEquals(HttpStatusCode.Unauthorized, apiKeyStatus, "the x-api-key spelling of the turn key")
        assertEquals(before, upstream.requests.size, "splice's own turn key must never reach the vendor")
    }

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

    // Third DR-30 redo (codex adversarial verdict, 2026-08-31): whitespace tokens are not the only
    // syntax a downstream parser extracts credentials from. RFC 7235 auth-params delimit with "=",
    // DQUOTE and "," — so `Digest response=<key>` split on whitespace alone yields no token equal
    // to the key, passed the guard, and the raw header (key included) forwarded verbatim. The
    // split is the full delimiter class now; the mgmt key is splice-authored hex, so a delimiter
    // can never occur INSIDE the key and the class split can surface it but never break it.

    @Test
    fun `an auth-param spelling of the key is refused - Digest response equals key`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, body) = turn(port, mapOf("Authorization" to "Digest response=$MGMT_KEY"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertTrue(body.contains("management key"), body)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `a quoted auth-param spelling of the key is refused`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to "Digest response=\"$MGMT_KEY\""))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `a comma-delimited auth-param list carrying the key is refused`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val (status, _) = turn(port, mapOf("Authorization" to "Digest realm=proxy,response=$MGMT_KEY,qop=auth"))
        assertEquals(HttpStatusCode.Unauthorized, status)
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `a delimiter-rich credential that is NOT the key still forwards verbatim`() {
        // The widened split must not over-refuse: a legitimate auth-param header with the same
        // shape and a different credential rides upstream untouched, byte-for-byte.
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val header = "Digest username=\"alice\", response=caller-own-digest, qop=auth"
        val (status, _) = turn(port, mapOf("Authorization" to header))
        assertEquals(HttpStatusCode.OK, status)
        assertEquals(before + 1, upstream.requests.size, "one turn must produce one upstream request")
        assertEquals(listOf(header), upstream.requests[before]["authorization"].orEmpty())
    }

    // v0.4.0 review: the check read the FIRST line of each credential header while the forwarder
    // sends the first NON-BLANK one, so an empty line ahead of the key passed the check and the key
    // rode upstream. What is checked is now what is forwarded, read from the same function.
    @Test
    fun `a blank Authorization line ahead of the key cannot smuggle it upstream`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val response = rawTurn(port, listOf("Authorization" to "", "Authorization" to "Bearer $TURN_KEY"))
        assertTrue(response.startsWith("HTTP/1.1 401"), response.lineSequence().first())
        assertEquals(before, upstream.requests.size, "splice's own turn key must never reach the vendor")
    }

    @Test
    fun `a whitespace-only Authorization line ahead of the key cannot smuggle it upstream`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val response = rawTurn(port, listOf("Authorization" to "   ", "Authorization" to "Basic $MGMT_KEY"))
        assertTrue(response.startsWith("HTTP/1.1 401"), response.lineSequence().first())
        assertEquals(before, upstream.requests.size, "splice's own key must never reach the vendor")
    }

    @Test
    fun `a blank x-api-key line ahead of the key cannot smuggle it upstream`() {
        val port = startHead(forwardClientAuth = true)
        val before = upstream.requests.size
        val response = rawTurn(
            port,
            listOf("Authorization" to "Bearer caller-own-token", "x-api-key" to "", "x-api-key" to MGMT_KEY),
        )
        assertTrue(response.startsWith("HTTP/1.1 401"), response.lineSequence().first())
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
