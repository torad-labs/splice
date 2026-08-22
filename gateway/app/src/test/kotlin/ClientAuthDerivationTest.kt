// NEW: the DERIVATION wall under the client-auth bypass (campaign claude-head follow-up).
//
// `forwardClientAuth` opens the mgmt-key front door, and it is only safe because splice holds NO
// credential for the head it opens. Those are ONE fact, so Daemon.assembleHead derives the flag
// from the wired credential — `wired.auth is ClientAuthProvider` — and never from the declared
// `auth.kind` string.
//
// This is the configuration that makes the difference observable: `auth.kind = "client"` on the
// openai-chat dialect. ProviderAssembly.buildProvider dispatches on DIALECT first, and only the
// anthropic-passthrough arm builds a ClientAuthProvider, so this head is wired with
// ApiKeyAuthProvider — it HOLDS a credential. Deriving the flag from the string opened its door
// anyway (a head that both bypasses the check AND carries a real vendor key); deriving it from the
// credential cannot. Both heads ride ONE daemon, so the contrast is a single boot.
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.Daemon
import splice.app.TopologyLoader
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors

/** An Anthropic Messages upstream, just enough for the passthrough head to complete one turn. */
private class TinyAnthropicUpstream {
    private val server = com.sun.net.httpserver.HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.executor = pool
        server.createContext("/") { ex ->
            val _ = ex.requestBody.readAllBytes()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            listOf(
                """{"type":"message_start","message":{"usage":{"input_tokens":1}}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                """{"type":"message_stop"}""",
            ).forEach { ex.responseBody.write("data: $it\n\n".toByteArray()) }
            Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
            Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientAuthDerivationTest {

    private val upstream = TinyAnthropicUpstream()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private val controlPort = freshPort()
    private val passthroughPort = freshPort()
    private val strayPort = freshPort()

    // `stray` declares the SAME auth kind as `anthropic` on a dialect whose dispatch has no client
    // arm — the one hand-authored config where the declaration and the wiring disagree.
    private fun topologyToml(): String = """
        [daemon]
        control_port = $controlPort

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "client" }
        extra_headers = { anthropic-version = "2023-06-01" }
        [[providers.anthropic.models]]
        id = "claude-fable-5"
        context_window = 200000

        [providers.stray]
        dialect = "openai-chat"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "client" }
        [[providers.stray.models]]
        id = "some/model"
        context_window = 128000

        [heads.claude-splice]
        provider = "anthropic"
        port = $passthroughPort
        discovery_prefix = "claude-splice--"
        pinned_model = "claude-fable-5"

        [heads.stray]
        provider = "stray"
        port = $strayPort
        discovery_prefix = "claude-stray--"
        pinned_model = "some/model"
    """.trimIndent()

    @BeforeAll
    fun setUp() {
        upstream.start()
        val tmp = Files.createTempDirectory("client-auth-derivation")
        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml()),
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            dashboardHtml = { "<!doctype html>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, passthroughPort, strayPort)
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        client.close()
        upstream.stop()
    }

    private fun turn(port: Int, model: String) = runBlocking {
        val response = client.post("http://127.0.0.1:$port/v1/messages") {
            // deliberately NOT the mgmt key — this is the door under test
            header("Authorization", "Bearer callers-own-credential")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"$model","max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"hi"}],"stream":true}""",
            )
        }
        response.status to response.bodyAsText()
    }

    @Test
    fun `a head wired with an api-key provider keeps the mgmt-key door though it declares client auth`() {
        val (status, body) = turn(strayPort, "claude-stray--some/model")
        assertEquals(HttpStatusCode.Unauthorized, status, body)
        // the mgmt-key door's own message: not a vendor 401, not a shape rejection
        assertTrue(body.contains("invalid local gateway credentials"), body)
    }

    @Test
    fun `the head actually wired with ClientAuthProvider still bypasses it`() {
        val (status, body) = turn(passthroughPort, "claude-splice--claude-fable-5")
        assertEquals(HttpStatusCode.OK, status, body)
    }
}
