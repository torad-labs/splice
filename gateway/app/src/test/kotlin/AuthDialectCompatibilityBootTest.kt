// NEW: the daemon-level wall under the auth-kind/dialect compatibility matrix. A registered kind
// on an incompatible dialect must fail that head during assembly, with a diagnostic naming the
// head, kind, and dialect. Per-head boot isolation keeps the daemon and valid sibling serving: the
// invalid head is down loudly rather than silently falling through to ApiKeyAuthProvider.
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.CopyOnWriteArrayList
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
class AuthDialectCompatibilityBootTest {

    private val upstream = TinyAnthropicUpstream()
    private val client = HttpClient(CIO)
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var daemon: Daemon
    private val controlPort = freshPort()
    private val passthroughPort = freshPort()
    private val strayPort = freshPort()

    // `stray` declares a registered kind on an incompatible dialect; assembly must reject this head
    // before the chat arm can silently reinterpret it as API-key auth.
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
        val tmp = Files.createTempDirectory("auth-dialect-compatibility")
        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml()),
            statePaths = StatePaths(baseOverride = tmp.resolve("state")),
            dashboardHtml = { "<!doctype html>" },
            log = logs::add,
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, passthroughPort)
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
    fun `an incompatible known kind fails only that head and names the rejected tuple`() = runBlocking {
        val health = Json.parseToJsonElement(
            client.get("http://127.0.0.1:$controlPort/health").bodyAsText(),
        ).jsonObject
        assertEquals(2, health.getValue("heads").jsonPrimitive.content.toInt())
        assertEquals(1, health.getValue("readyHeads").jsonPrimitive.content.toInt())
        assertEquals(1, health.getValue("failedHeads").jsonPrimitive.content.toInt())

        val bootLog = logs.joinToString("")
        assertTrue(bootLog.contains("stray"), bootLog)
        assertTrue(bootLog.contains("client"), bootLog)
        assertTrue(bootLog.contains("openai-chat"), bootLog)
    }

    @Test
    fun `a compatible client passthrough head still serves on caller auth`() {
        val (status, body) = turn(passthroughPort, "claude-splice--claude-fable-5")
        assertEquals(HttpStatusCode.OK, status, body)
    }
}
