// NEW: gate-runnable claude-head E2E. A real Daemon from TOML against a recording Anthropic
// that never bills. MultiProviderDaemonTest pins assembly + one text turn + header forward;
// this file pins the user-facing contract a broken merge would cost real users: the SSE
// `event:` sequence, cache_control on the upstream BODY, thinking, a tool_use → tool_result
// round-trip that keeps the same caller Authorization, count_tokens as a local estimate
// (zero upstream), and the mgmt key never riding to the vendor.
package splice.app.e2e

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mock.awaitListening
import mock.freshPort
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.Daemon
import splice.app.TopologyLoader
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private const val CALLER = "callers-own-credential"
private const val MODEL = "claude-splice--claude-haiku-4-5"

private data class Captured(
    val path: String,
    val headers: Map<String, List<String>>,
    val body: String,
)

/** Anthropic Messages stand-in: records headers AND bodies, serves scripted `data:` frames.
 *  The translator keys on JSON `type`, not the `event:` prefix, so this matches the live vendor. */
private class RecordingAnthropic {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val scripts = ConcurrentLinkedQueue<List<String>>()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    val seen = CopyOnWriteArrayList<Captured>()

    init {
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun enqueue(frames: List<String>) {
        scripts.add(frames)
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        seen.add(
            Captured(
                path = ex.requestURI.path,
                headers = ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() },
                body = body,
            ),
        )
        val frames = scripts.poll() ?: AnthropicFrames.text("ok")
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        frames.forEach { ex.responseBody.write("data: $it\n\n".toByteArray()) }
        Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
        Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
    }
}

private object AnthropicFrames {
    fun text(text: String): List<String> = listOf(
        """{"type":"message_start","message":{"usage":{"input_tokens":2}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
    )

    fun thinkingThenText(thought: String, text: String): List<String> = listOf(
        """{"type":"message_start","message":{"usage":{"input_tokens":2}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"$thought"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"content_block_start","index":1,"content_block":{"type":"text"}}""",
        """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"$text"}}""",
        """{"type":"content_block_stop","index":1}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""",
        """{"type":"message_stop"}""",
    )

    fun toolUse(id: String, name: String): List<String> = listOf(
        """{"type":"message_start","message":{"usage":{"input_tokens":2}}}""",
        """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"$id","name":"$name"}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"x\"}"}}""",
        """{"type":"content_block_stop","index":0}""",
        """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":1}}""",
        """{"type":"message_stop"}""",
    )
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeHeadDaemonE2ETest {

    private val upstream = RecordingAnthropic()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private lateinit var mgmtKey: String
    private val controlPort = freshPort()
    private val headPort = freshPort()

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("claude-head-e2e")
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        mgmtKey = MgmtKey(statePaths).get()
        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml()),
            statePaths = statePaths,
            dashboardHtml = { "<!doctype html>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("e2e-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, headPort)
    }

    private fun topologyToml(): String = """
        [daemon]
        control_port = $controlPort

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "client" }
        extra_headers = { anthropic-version = "2023-06-01" }
        [[providers.anthropic.models]]
        id = "claude-haiku-4-5"
        context_window = 200000

        [heads.claude-splice]
        provider = "anthropic"
        port = $headPort
        discovery_prefix = "claude-splice--"
        pinned_model = "claude-haiku-4-5"
    """.trimIndent()

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        client.close()
        upstream.stop()
    }

    @Test
    fun `a text turn emits the Anthropic SSE contract and forwards the caller headers`() = runBlocking {
        val before = upstream.seen.size
        upstream.enqueue(AnthropicFrames.text("hello-from-mock"))
        val body = turn(
            """{"model":"$MODEL","max_tokens":16,"stream":true,""" +
                """"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertSseContract(body)
        assertTrue(body.contains("hello-from-mock"), body)

        assertEquals(before + 1, upstream.seen.size)
        val sent = upstream.seen[before]
        assertTrue(sent.path.endsWith("/v1/messages"), sent.path)
        assertEquals(listOf("Bearer $CALLER"), sent.headers["authorization"])
        assertEquals(listOf("oauth-2025-04-20"), sent.headers["anthropic-beta"])
        assertEquals(listOf("2023-06-01"), sent.headers["anthropic-version"])
        assertNoVendorLeak(sent)
    }

    @Test
    fun `cache_control on system tools and messages reaches the upstream body`() = runBlocking {
        val before = upstream.seen.size
        upstream.enqueue(AnthropicFrames.text("cached"))
        turn(
            """{"model":"$MODEL","max_tokens":16,"stream":true,""" +
                """"system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],""" +
                """"tools":[{"name":"lookup","description":"d",""" +
                """"input_schema":{"type":"object","properties":{}},""" +
                """"cache_control":{"type":"ephemeral"}}],""" +
                """"messages":[{"role":"user","content":[""" +
                """{"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}]}]}""",
        )
        val sent = upstream.seen[before]
        assertTrue(sent.body.contains("cache_control"), "neutral head must preserve cache_control: ${sent.body}")
        assertTrue(sent.body.contains("ephemeral"), sent.body)
        assertTrue(sent.body.contains("\"name\":\"lookup\""), sent.body)
    }

    @Test
    fun `thinking config is forwarded and thinking_delta is visible to the client`() = runBlocking {
        val before = upstream.seen.size
        upstream.enqueue(AnthropicFrames.thinkingThenText("ponder", "ok"))
        val body = turn(
            """{"model":"$MODEL","max_tokens":64,"stream":true,""" +
                """"thinking":{"type":"enabled","budget_tokens":1024},""" +
                """"messages":[{"role":"user","content":"think"}]}""",
        )
        assertTrue(body.contains("thinking_delta"), body)
        assertTrue(body.contains("ponder"), body)
        assertTrue(body.contains("ok"), body)
        val sent = upstream.seen[before]
        assertTrue(sent.body.contains("\"type\":\"enabled\""), "must not rewrite to adaptive: ${sent.body}")
        assertTrue(sent.body.contains("1024"), sent.body)
        assertFalse(sent.body.contains("\"type\":\"adaptive\""), sent.body)
    }

    @Test
    fun `a tool_use turn then a tool_result follow-up keeps the same caller Authorization`() = runBlocking {
        val before = upstream.seen.size
        upstream.enqueue(AnthropicFrames.toolUse("toolu_1", "lookup"))
        upstream.enqueue(AnthropicFrames.text("done"))
        val first = turn(
            """{"model":"$MODEL","max_tokens":16,"stream":true,""" +
                """"tools":[{"name":"lookup","description":"d",""" +
                """"input_schema":{"type":"object","properties":{"q":{"type":"string"}}}}],""" +
                """"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertTrue(first.contains("tool_use"), first)
        assertTrue(first.contains("lookup"), first)
        assertTrue(first.contains("toolu_1"), first)

        val second = turn(
            """{"model":"$MODEL","max_tokens":16,"stream":true,""" +
                """"messages":[""" +
                """{"role":"user","content":"hi"},""" +
                """{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1",""" +
                """"name":"lookup","input":{"q":"x"}}]},""" +
                """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1",""" +
                """"content":"found"}]}]}""",
        )
        assertTrue(second.contains("done"), second)
        assertEquals(before + 2, upstream.seen.size)
        val firstUp = upstream.seen[before]
        val secondUp = upstream.seen[before + 1]
        assertEquals(listOf("Bearer $CALLER"), firstUp.headers["authorization"])
        assertEquals(listOf("Bearer $CALLER"), secondUp.headers["authorization"])
        assertTrue(secondUp.body.contains("tool_result"), secondUp.body)
        assertTrue(secondUp.body.contains("toolu_1"), secondUp.body)
        assertNoVendorLeak(firstUp)
        assertNoVendorLeak(secondUp)
    }

    @Test
    fun `count_tokens works without a mgmt key and creates no upstream request`() = runBlocking {
        val before = upstream.seen.size
        val response = client.post("http://127.0.0.1:$headPort/v1/messages/count_tokens") {
            header("Content-Type", "application/json")
            setBody(
                """{"model":"$MODEL","messages":[{"role":"user","content":"hello"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(payload["input_tokens"]!!.jsonPrimitive.int >= 1, payload.toString())
        assertEquals(before, upstream.seen.size, "count_tokens is a local estimate — it must not POST upstream")
    }

    @Test
    fun `models discovery opens without a mgmt key`() = runBlocking {
        val body = client.get("http://127.0.0.1:$headPort/v1/models") {
            header("Authorization", "Bearer $CALLER")
        }.bodyAsText()
        assertTrue(body.contains(MODEL), body)
    }

    private suspend fun turn(json: String): String =
        client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Authorization", "Bearer $CALLER")
            header("anthropic-beta", "oauth-2025-04-20")
            header("Content-Type", "application/json")
            setBody(json)
        }.bodyAsText()

    private fun assertSseContract(sse: String) {
        val names = sse.lineSequence()
            .mapNotNull { line ->
                if (line.startsWith("event: ")) line.removePrefix("event: ").trim() else null
            }
            .toList()
        val substantive = names.filter { it != "ping" }
        assertTrue(substantive.isNotEmpty(), "no SSE events: $sse")
        assertEquals("message_start", substantive.first(), sse)
        assertEquals("message_stop", names.last(), sse)
        assertTrue("content_block_start" in names, sse)
        assertTrue("content_block_delta" in names, sse)
        assertTrue("content_block_stop" in names, sse)
        assertTrue("message_delta" in names, sse)
    }

    private fun assertNoVendorLeak(sent: Captured) {
        val flat = sent.headers.values.flatten()
        assertFalse(flat.any { it.contains(mgmtKey) }, "mgmt key leaked upstream: ${sent.headers}")
        assertTrue(sent.headers["x-api-key"].isNullOrEmpty(), sent.headers.toString())
        assertTrue(sent.headers.keys.none { it.startsWith("x-msh-") }, sent.headers.keys.toString())
        assertTrue(sent.headers["user-agent"].orEmpty().none { it.contains("KimiCLI") }, sent.headers.toString())
    }
}
