// NEW (P6-TOML): the zero-code multi-provider proof. One daemon, THREE heads defined purely in
// TOML — codex (responses + chatgpt-oauth), grok (responses + api-key, session cache key), and
// openrouter (openai-chat + api-key) — assembled by the (dialect, auth-kind) dispatch with NO new
// Kotlin per vendor. Proves each head is built with the RIGHT provider + auth: all three reachable
// on their ports, /api/heads reports each authKind, and a real turn to the openrouter head hits the
// chat-shaped upstream with its api-key bearer (i.e. it was wired as chat, not codex).
package multiprovider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import mock.MockChatGptUpstream
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
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.Cancellables
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/** A minimal OpenAI Chat Completions upstream (records the auth header the daemon sent). */
private class ChatUpstream {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    val auths = mutableListOf<String>()

    init {
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        ex.requestHeaders.getFirst("Authorization")?.let { auths.add(it) }
        val _ = ex.requestBody.readBytes() // drain the request; content is irrelevant
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.write("""data: {"choices":[{"delta":{"content":"hi from chat"}}]}""".toByteArray())
        ex.responseBody.write("\n\n".toByteArray())
        ex.responseBody.write(
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2}}"""
                .toByteArray(),
        )
        ex.responseBody.write("\n\n".toByteArray())
        Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
        Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
    }
}

/** An Anthropic Messages upstream that records what the daemon forwarded (campaign claude-head). */
private class AnthropicUpstream {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    val seen = mutableListOf<Map<String, List<String>>>()

    init {
        server.executor = pool
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        seen.add(ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.toList() })
        val _ = ex.requestBody.readBytes()
        ex.responseHeaders.add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, 0)
        val frames = listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":2}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"text"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
            """{"type":"message_stop"}""",
        )
        frames.forEach { ex.responseBody.write("data: $it\n\n".toByteArray()) }
        Cancellables.discard(runCatching { ex.responseBody.close() }, "test-server teardown")
        Cancellables.discard(runCatching { ex.close() }, "test-server teardown")
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiProviderDaemonTest {

    private val codexMock = MockChatGptUpstream()
    private val grokMock = MockChatGptUpstream() // grok is the responses dialect too
    private val chatMock = ChatUpstream()
    private val anthropicMock = AnthropicUpstream()
    private val client = HttpClient(CIO)
    private lateinit var daemon: Daemon
    private lateinit var key: String
    private val controlPort = freshPort()
    private val codexPort = freshPort()
    private val grokPort = freshPort()
    private val chatPort = freshPort()
    private val claudePort = freshPort()

    @BeforeAll
    fun setUp() {
        val tmp = Files.createTempDirectory("multi")
        val codexAuth = tmp.resolve("codex.json")
        Files.writeString(codexAuth, """{"tokens":{"access_token":"codex-tok","account_id":"a","refresh_token":"r"}}""")
        val grokKey = tmp.resolve("grok.json")
        Files.writeString(grokKey, """{"api_key":"xai-daemon-key"}""")
        val orKey = tmp.resolve("or.json")
        Files.writeString(orKey, """{"api_key":"or-daemon-key"}""")
        val statePaths = StatePaths(baseOverride = tmp.resolve("state"))
        key = MgmtKey(statePaths).get()

        daemon = Daemon(
            topology = TopologyLoader.parse(topologyToml(codexAuth, grokKey, orKey)),
            statePaths = statePaths,
            dashboardHtml = { "<!doctype html>" },
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { daemon.start() }
        awaitListening(controlPort, codexPort, grokPort, chatPort, claudePort)
    }

    // THE POINT: grok + openrouter are NEW vendors added as pure TOML — no new Kotlin per vendor.
    private fun topologyToml(codexAuth: Path, grokKey: Path, orKey: Path): String =
        providersToml(codexAuth, grokKey, orKey) + "\n" + headsToml()

    private fun providersToml(codexAuth: Path, grokKey: Path, orKey: Path): String = """
        [daemon]
        control_port = $controlPort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${codexMock.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "${codexAuth.esc()}" }
        quirks = { account_id_header = true, cache_key = "first-message-hash", summary_field = true }
        [[providers.codex.models]]
        id = "gpt-5.6-sol"
        context_window = 272000

        [providers.xai]
        dialect = "openai-responses"
        base_url = "${grokMock.baseUrl}"
        auth = { kind = "api-key", file = "${grokKey.esc()}" }
        quirks = { cache_key = "session-id", effort_ceiling = "high", summary_field = true }
        [[providers.xai.models]]
        id = "grok-4.5"
        context_window = 1000000

        [providers.openrouter]
        dialect = "openai-chat"
        base_url = "${chatMock.baseUrl}"
        auth = { kind = "api-key", file = "${orKey.esc()}" }
        [[providers.openrouter.models]]
        id = "meta/llama-4"
        context_window = 128000

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "${anthropicMock.baseUrl}"
        auth = { kind = "client" }
        extra_headers = { anthropic-version = "2023-06-01" }
        [[providers.anthropic.models]]
        id = "claude-fable-5"
        context_window = 200000

    """.trimIndent()

    private fun headsToml(): String = """
        [heads.claudex]
        provider = "codex"
        port = $codexPort
        discovery_prefix = "claude-codex--"
        pinned_model = "gpt-5.6-sol"

        [heads.grok]
        provider = "xai"
        port = $grokPort
        discovery_prefix = "claude-grok--"
        pinned_model = "grok-4.5"

        [heads.openrouter]
        provider = "openrouter"
        port = $chatPort
        discovery_prefix = "claude-openrouter--"
        pinned_model = "meta/llama-4"

        [heads.claude-splice]
        provider = "anthropic"
        port = $claudePort
        discovery_prefix = "claude-splice--"
        pinned_model = "claude-fable-5"
    """.trimIndent()

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        client.close()
        codexMock.stop()
        grokMock.stop()
        chatMock.stop()
        anthropicMock.stop()
    }

    @Test
    fun `all three TOML-defined heads are assembled and reachable`() = runBlocking {
        for (p in listOf(codexPort, grokPort, chatPort)) {
            assertTrue(client.get("http://127.0.0.1:$p/health").bodyAsText().contains("\"ok\":true"), "head on $p")
        }
        val heads = client.get("http://127.0.0.1:$controlPort/api/heads") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertTrue(heads.contains("claudex") && heads.contains("grok") && heads.contains("openrouter"))
        assertTrue(heads.contains("chatgpt-oauth")) // codex authKind
        assertTrue(heads.contains("api-key")) // grok + openrouter authKind
    }

    @Test
    fun `the openrouter head was wired as openai-chat - its turn hits the chat upstream with its api key`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$chatPort/v1/messages") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            setBody(
                """{"model":"claude-openrouter--meta/llama-4","stream":true,"max_tokens":100,
                    "messages":[{"role":"user","content":"hi"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("hi from chat")) // the chat translator drove the turn
        assertTrue(sse.contains("event: message_stop"))
        assertTrue(chatMock.auths.any { it == "Bearer or-daemon-key" }) // openrouter's api key, not codex's
    }

    @Test
    fun `the grok head was wired with api-key auth against the responses dialect`() = runBlocking {
        val sse = client.post("http://127.0.0.1:$grokPort/v1/messages") {
            header("Content-Type", "application/json")
            header("Authorization", "Bearer $key")
            header("x-claude-code-session-id", "sess-daemon")
            setBody(
                """{"model":"claude-grok--grok-4.5","stream":true,
                    "system":"You are a test. SCENARIO:basic","messages":[{"role":"user","content":"go"}]}""",
            )
        }.bodyAsText()
        assertTrue(sse.contains("ok after auth"))
        assertTrue(grokMock.upstreamAuths.any { it.second == "Bearer xai-daemon-key" }) // grok api key
        assertTrue(grokMock.upstreamBodies.last().second.contains("claude-grok:sess-daemon")) // session cache key
    }
    // ── the claude head, end to end through a REAL daemon (campaign claude-head, CH-10) ────────
    //
    // The sharpest form of this file's own claim: a head whose upstream is Anthropic itself,
    // added with no Kotlin at all — no provider class, no auth wiring, no quirks — and serving on
    // the CALLER's credential rather than one splice holds.

    @Test
    fun `the claude head serves a turn on the caller's own credential`() = runBlocking {
        val before = anthropicMock.seen.size
        val body = client.post("http://127.0.0.1:$claudePort/v1/messages") {
            // deliberately NOT the mgmt key: this head has no splice-held credential to present
            header("Authorization", "Bearer callers-own-credential")
            header("anthropic-beta", "oauth-2025-04-20")
            header("Content-Type", "application/json")
            setBody(
                """{"model":"claude-splice--claude-fable-5","max_tokens":16,""" +
                    """"messages":[{"role":"user","content":"hi"}],"stream":true}""",
            )
        }.bodyAsText()
        assertTrue(body.contains("hi"), body)

        assertEquals(before + 1, anthropicMock.seen.size, "one turn must produce one upstream request")
        val sent = anthropicMock.seen[before]
        // the caller's credential rode through, exactly once
        assertTrue(sent["authorization"] == listOf("Bearer callers-own-credential"), sent.toString())
        assertTrue(sent["anthropic-beta"] == listOf("oauth-2025-04-20"), sent.toString())
        assertTrue(sent["anthropic-version"] == listOf("2023-06-01"), sent.toString())
        // and none of kimi's vendor identity leaked onto a head that never declared it
        assertTrue(sent.keys.none { it.startsWith("x-msh-") }, sent.keys.toString())
        assertTrue(sent["user-agent"].orEmpty().none { it.contains("KimiCLI") }, sent.toString())
    }

    @Test
    fun `the claude head is assembled by a real daemon from TOML alone`() = runBlocking {
        val heads = client.get("http://127.0.0.1:$controlPort/api/heads") {
            header("Authorization", "Bearer $key")
        }.bodyAsText()
        assertTrue(heads.contains("claude-splice"), heads)
        assertTrue(client.get("http://127.0.0.1:$claudePort/health").bodyAsText().contains("\"ok\":true"))
    }
}

private fun Path.esc() = toString().replace("\\", "/")
