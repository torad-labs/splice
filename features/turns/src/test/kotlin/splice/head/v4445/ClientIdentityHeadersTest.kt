// NEW: V4-445 — the wrapped client's identity must survive the actual local upstream HTTP hop,
// while other heads retain their own identity and splice's local keys never become credentials.
package splice.head.v4445

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.ClientAuthProvider
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.turn.WatchdogBudget
import splice.dialect.anthropic.PassthroughProvider
import splice.dialect.anthropic.PassthroughQuirks
import splice.head.HeadDeps
import splice.head.HeadServer
import splice.head.headDeps
import splice.upstream.ProviderTuning
import splice.upstream.retry.InflightGate
import splice.upstream.transport.UpstreamClient
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

private const val MANAGEMENT_KEY = "identity-test-management-key"
private const val TURN_KEY = "identity-test-turn-key"
private const val MODEL = "claude-sonnet-5"
private const val PROVIDER_AGENT = "provider-authored-agent"

private val CLIENT_IDENTITY = mapOf(
    "User-Agent" to "claude-cli/2.1.285 (external, sdk-cli)",
    "X-App" to "cli",
    "X-Claude-Code-Session-Id" to "identity-session-445",
    "Anthropic-Dangerous-Direct-Browser-Access" to "true",
    "X-Stainless-Arch" to "x64",
    "X-Stainless-Lang" to "js",
    "X-Stainless-Os" to "Linux",
    "X-Stainless-Package-Version" to "0.74.0",
    "X-Stainless-Retry-Count" to "7",
    "X-Stainless-Runtime" to "node",
    "X-Stainless-Runtime-Version" to "v22.17.0",
    "X-Stainless-Timeout" to "600",
    // A family, not a frozen SDK-version list.
    "X-Stainless-Future-Identity" to "caller-selected-value",
)

class ClientIdentityHeadersTest {
    @Test
    fun `client identity reaches the upstream verbatim and once including SDK family members`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = true) { rig ->
            assertEquals(HttpStatusCode.OK, rig.turn(CLIENT_IDENTITY).first)
            assertEquals(1, rig.requests.size)
            val sent = rig.requests.single()
            CLIENT_IDENTITY.forEach { (name, value) ->
                assertEquals(listOf(value), sent[name.lowercase()], name)
            }
            assertEquals(listOf("Bearer caller-own-token"), sent["authorization"])
            assertFalse(sent.containsKey("x-unrelated-client-header"))
        }
    }

    @Test
    fun `identity is not invented when the client sends none`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = true, staticHeaders = emptyMap()) { rig ->
            assertEquals(HttpStatusCode.OK, rig.turn(emptyMap()).first)
            val sent = rig.requests.single()
            CLIENT_IDENTITY.forEach { (name, value) ->
                if (name == "User-Agent") {
                    assertFalse(sent["user-agent"].orEmpty().contains(value), sent.toString())
                } else {
                    assertFalse(sent.containsKey(name.lowercase()), name)
                }
            }
        }
    }

    @Test
    fun `other heads forward no client identity`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = false, staticHeaders = emptyMap()) { rig ->
            assertEquals(HttpStatusCode.OK, rig.turn(CLIENT_IDENTITY).first)
            val sent = rig.requests.single()
            CLIENT_IDENTITY.forEach { (name, value) ->
                if (name == "User-Agent") {
                    assertFalse(sent["user-agent"].orEmpty().contains(value), sent.toString())
                } else {
                    assertFalse(sent.containsKey(name.lowercase()), name)
                }
            }
            assertEquals(listOf("provider-owned-key"), sent["x-api-key"])
            assertFalse(sent.containsKey("authorization"))
        }
    }

    @Test
    fun `other heads keep their provider-authored identity`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = false) { rig ->
            assertEquals(HttpStatusCode.OK, rig.turn(CLIENT_IDENTITY).first)
            val sent = rig.requests.single()
            assertEquals(listOf(PROVIDER_AGENT), sent["user-agent"])
            assertFalse(sent.containsKey("x-app"))
            assertFalse(sent.containsKey("x-claude-code-session-id"))
        }
    }

    @Test
    fun `splice retries leave the client's SDK retry count unchanged`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = true, failFirst = true) { rig ->
            assertEquals(HttpStatusCode.OK, rig.turn(CLIENT_IDENTITY).first)
            assertEquals(2, rig.requests.size)
            rig.requests.forEach { sent ->
                assertEquals(listOf("7"), sent["x-stainless-retry-count"])
                assertEquals(listOf(CLIENT_IDENTITY.getValue("User-Agent")), sent["user-agent"])
            }
        }
    }

    @Test
    fun `both local keys in every existing credential carrier remain refused with identity present`(
        @TempDir tmp: Path,
    ) {
        withHead(tmp, clientAuth = true) { rig ->
            for (key in listOf(MANAGEMENT_KEY, TURN_KEY)) {
                for (carrier in listOf("Authorization", "x-api-key", "anthropic-version", "anthropic-beta")) {
                    val value = if (carrier == "Authorization") "Digest response=\"$key\"" else key
                    val (status, body) = rig.turn(CLIENT_IDENTITY + (carrier to value))
                    assertEquals(HttpStatusCode.Unauthorized, status, carrier)
                    assertTrue(body.contains("splice's own keys"), body)
                }
            }
            assertTrue(rig.requests.isEmpty(), "no local credential reaches the upstream")
        }
    }

    @Test
    fun `new identity carriers cannot leak either local key`(@TempDir tmp: Path) {
        withHead(tmp, clientAuth = true) { rig ->
            for (key in listOf(MANAGEMENT_KEY, TURN_KEY)) {
                for (carrier in listOf("User-Agent", "X-Stainless-Future-Identity")) {
                    val (status, body) = rig.turn(CLIENT_IDENTITY + (carrier to "test $key"))
                    assertEquals(HttpStatusCode.Unauthorized, status, carrier)
                    assertTrue(body.contains(carrier, ignoreCase = true), body)
                }
            }
            assertTrue(rig.requests.isEmpty(), "new headers must not create a local-key leak")
        }
    }

    private fun recordingUpstream(
        requests: CopyOnWriteArrayList<Map<String, List<String>>>,
        failFirst: Boolean,
    ): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/messages") { exchange ->
            exchange.requestBody.readAllBytes()
            requests += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() }
            val failed = failFirst && requests.size == 1
            val body = if (failed) {
                """{"error":{"type":"api_error","message":"synthetic retry"}}"""
            } else {
                RESPONSE
            }
            exchange.responseHeaders.add("Content-Type", if (failed) "application/json" else "text/event-stream")
            exchange.responseHeaders.add("Retry-After", "0")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (failed) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        return server
    }

    private fun withHead(
        tmp: Path,
        clientAuth: Boolean,
        staticHeaders: Map<String, String> = mapOf("User-Agent" to PROVIDER_AGENT),
        failFirst: Boolean = false,
        test: (IdentityRig) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<Map<String, List<String>>>()
        val upstream = recordingUpstream(requests, failFirst)
        upstream.start()
        val auth = if (clientAuth) ClientAuthProvider("claude-splice") else IdentityProviderAuth()
        val provider = PassthroughProvider(
            ProviderTuning(
                key = "anthropic",
                label = "claude-splice",
                catalog = ModelCatalog(
                    discoveryPrefix = "claude-splice--",
                    models = listOf(ModelEntry(MODEL, "Claude Sonnet", contextWindow = 200_000)),
                    defaultContextWindow = 200_000,
                ),
                pinnedModel = MODEL,
                auth = auth,
                baseUrl = "http://127.0.0.1:${upstream.address.port}",
                watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
            ),
            PassthroughQuirks(providerTag = "test-identity"),
            staticHeaders = staticHeaders,
        )
        val head = HeadServer(
            provider,
            listenPort = 0,
            deps = headDeps(
                tmp = tmp,
                upstream = UpstreamClient(totalTimeoutMs = 30_000, maxRetries = 2),
                gate = InflightGate({ 1 }),
            ).copy(
                policy = HeadDeps.HeadPolicy(forwardClientAuth = clientAuth),
            ).copy(inferenceToken = TURN_KEY, operatorToken = MANAGEMENT_KEY),
        )
        val client = HttpClient(CIO)
        try {
            runBlocking { head.start() }
            test(IdentityRig(head.port, clientAuth, client, requests))
        } finally {
            runBlocking { head.stop() }
            client.close()
            upstream.stop(0)
        }
    }
}

private class IdentityRig(
    private val port: Int,
    private val clientAuth: Boolean,
    private val client: HttpClient,
    val requests: List<Map<String, List<String>>>,
) {
    fun turn(identity: Map<String, String>): Pair<HttpStatusCode, String> = runBlocking {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            header("Authorization", if (clientAuth) "Bearer caller-own-token" else "Bearer $TURN_KEY")
            identity.forEach { (name, value) -> headers[name] = value }
            header("x-unrelated-client-header", "not-forwardable")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-splice--$MODEL","max_tokens":16,"messages":[{"role":"user","content":"hi"}],"stream":true}""",
            )
        }
        response.status to response.bodyAsText()
    }
}

private class IdentityProviderAuth : RefreshableAuthProvider {
    override suspend fun credentials(): Credentials = Credentials.ApiKey("provider-owned-key", "x-api-key", "")
    override suspend fun refresh(): Credentials = credentials()
    override suspend fun describe(): AuthDescription = AuthDescription(true, "identity-test")
}

private val RESPONSE = """
event: message_start
data: {"type":"message_start","message":{"usage":{"input_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

event: message_stop
data: {"type":"message_stop"}

""".trimStart()
