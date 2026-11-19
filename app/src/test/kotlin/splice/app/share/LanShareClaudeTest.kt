// LAN share (docs/specs/lan-share.md), for the head plain `claude` runs on, and what still holds since share-door-only
// (docs/specs/door-network.md, decision 5): a session on the sharing machine itself, signed in to Claude, names the
// OAuth beta once. The tests that drove that head at an address of its own (a node's launch and turn on it, the head
// offered at GET /) are gone with that share; their goals are shown through the door. Driven as it is used: two real
// daemons, torad (sharing) and mac (the node), in one home of their own, so neither reads this machine's ~/.claude;
// Anthropic is a stand-in that records what it was sent.
package splice.app.share

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.testing.TestPorts
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/** The token torad's own Claude sign-in (~/.claude) signs Anthropic requests with. */
private const val TORAD_LOGIN = "synthetic-torad-login"

/** The beta Anthropic requires beside a Claude subscription's OAuth token. */
private const val OAUTH_BETA = "oauth-2025-04-20"

private const val OPUS = "claude-opus-5-5"
private const val HEAD = "claude"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LanShareClaudeTest {
    private val anthropic = RecordingAnthropic()
    private val client = HttpClient(CIO)

    @AfterAll
    fun tearDown() {
        client.close()
        anthropic.stop()
    }

    @Test
    fun `a session on the server itself, signed in to Claude, names the OAuth beta once`(
        @TempDir tmp: Path,
    ) = machines(tmp) { m ->
        val (status, body) = m.serverTurn("$OAUTH_BETA,claude-code-20250219")
        assertEquals(200, status, body)
        val sent = anthropic.requests.last()
        assertEquals("Bearer $TORAD_LOGIN", sent["authorization"])
        val betas = sent["anthropic-beta"].orEmpty().split(',').map(String::trim)
        assertEquals(listOf(OAUTH_BETA, "claude-code-20250219"), betas, "the session's own flags, each once")
    }

    /** Both machines, started in [tmp]'s own home: torad signed in to Claude there, and mac's `claude` wrapped, its
     *  own Claude head beside a `[remote.claude]` on torad's. Stopped before the home is given back. */
    private fun machines(tmp: Path, body: (Machines) -> Unit) {
        val home = Files.createDirectories(tmp.resolve("home"))
        Files.createDirectories(home.resolve(".claude"))
        Files.writeString(home.resolve(".claude/.credentials.json"), credentials(TORAD_LOGIN))
        val ports = Ports()
        // torad runs as splice does on a machine: its state under the home its Claude sign-in is in, its config a file.
        val toradState = StatePaths(baseOverride = home.resolve(".splice/state"))
        val toradConfig = Files.createDirectories(tmp.resolve("torad-config")).resolve("splice.toml")
        Files.writeString(toradConfig, toradToml(ports))
        val macState = StatePaths(baseOverride = tmp.resolve("mac-state"))
        val realClaude = Files.writeString(Files.createDirectories(tmp.resolve("claude-bin")).resolve("claude"), "")
        realClaude.toFile().setExecutable(true)
        UserHome.within(home) {
            // mac's `claude` is wrapped, as on the Mac: the wrap's record sits in the home's default state folder.
            val wrap = StatePaths().stateDir.resolve("claude-head-wrap.json")
            check(wrap.startsWith(home)) { "the wrap record must stay in this test's home: $wrap" }
            Files.createDirectories(wrap.parent)
            Files.writeString(
                wrap,
                """{"real_binary_path":"$realClaude","shadowed_symlink_target":"$realClaude","shim_path":""}""",
            )
            val torad = daemon(toradToml(ports), toradState, toradConfig)
            val mac = daemon(macToml(ports), macState)
            runBlocking {
                torad.start()
                mac.start()
            }
            try {
                awaitListening(ports.toradControl, ports.toradClaude, ports.share, ports.macControl, ports.macClaude)
                body(Machines(ports))
            } finally {
                runBlocking {
                    mac.stop()
                    torad.stop()
                }
            }
        }
    }

    private fun daemon(toml: String, state: StatePaths, config: Path? = null) = Daemon(
        topology = TopologyLoader.parse(toml),
        statePaths = state,
        log = {},
        refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        topologyPath = config,
    )

    private inner class Machines(val ports: Ports) {
        /** A turn from a session on torad itself, whose Claude Code is signed in to torad's own login. */
        fun serverTurn(betas: String): Pair<Int, String> = runBlocking {
            val response = client.post("http://127.0.0.1:${ports.toradClaude}/v1/messages?beta=true") {
                header("Authorization", "Bearer $TORAD_LOGIN")
                header("anthropic-beta", betas)
                header("anthropic-version", "2023-06-01")
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"$OPUS","stream":true,"max_tokens":64,""" +
                        """"messages":[{"role":"user","content":"hi from torad"}]}""",
                )
            }
            response.status.value to response.bodyAsText()
        }
    }

    private inner class Ports {
        val toradControl = TestPorts.reserve()
        val toradClaude = TestPorts.reserve()
        val share = TestPorts.reserve()
        val macControl = TestPorts.reserve()
        val macClaude = TestPorts.reserve()
    }

    private fun toradToml(ports: Ports): String = """
        [daemon]
        control_port = ${ports.toradControl}

        [share]
        enabled = true
        port = ${ports.share}

        ${claudeHead(ports.toradClaude)}
    """.trimIndent()

    private fun macToml(ports: Ports): String = """
        [daemon]
        control_port = ${ports.macControl}

        ${claudeHead(ports.macClaude)}

        [remote.claude]
        url = "http://127.0.0.1:${ports.share}/$HEAD"
    """.trimIndent()

    /** A Claude head as splice.example.toml writes one: it forwards its caller's own login. */
    private fun claudeHead(port: Int): String = """
        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "${anthropic.baseUrl}"
        auth = { kind = "client" }
        extra_headers = { anthropic-version = "2023-06-01" }
        [[providers.anthropic.models]]
        id = "$OPUS"
        context_window = 1000000

        [heads.$HEAD]
        provider = "anthropic"
        port = $port
        discovery_prefix = "claude--"
        pinned_model = "$OPUS"
        [heads.$HEAD.claude]
        command = "$HEAD"
        [heads.$HEAD.overrides]
        quotaPoll = "off"
    """.trimIndent()

    private fun credentials(token: String): String =
        """{"claudeAiOauth":{"accessToken":"$token","refreshToken":"must-not-be-used","expiresAt":4102444800000}}"""

    private fun awaitListening(vararg ports: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        for (port in ports) {
            while (runCatching { Socket("127.0.0.1", port).use { } }.isFailure) {
                check(System.currentTimeMillis() < deadline) { "nothing listening on :$port" }
                LockSupport.parkNanos(5_000_000L)
            }
        }
    }
}

/** Anthropic's Messages endpoint, answering every turn with one short streamed message and keeping each turn's headers,
 *  names lowercased and repeated lines joined, as the head put them on the wire. */
private class RecordingAnthropic {
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            if (exchange.requestMethod != "POST" || !exchange.requestURI.path.endsWith("/v1/messages")) {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
                return@createContext
            }
            requests += exchange.requestHeaders.entries.associate { (name, values) ->
                name.lowercase() to values.joinToString(",")
            }
            val stream = STREAM.toByteArray()
            exchange.responseHeaders.add("content-type", "text/event-stream")
            exchange.sendResponseHeaders(200, stream.size.toLong())
            exchange.responseBody.use { it.write(stream) }
        }
        start()
    }
    val baseUrl = "http://127.0.0.1:${server.address.port}"

    fun stop() = server.stop(0)
}

private val STREAM = listOf(
    "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",""" +
        """"model":"$OPUS","content":[],"stop_reason":null,"usage":{"input_tokens":3,"output_tokens":0}}}""",
    "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"PONG"}}""",
    "content_block_stop" to """{"type":"content_block_stop","index":0}""",
    "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
    "message_stop" to """{"type":"message_stop"}""",
).joinToString("") { (event, data) -> "event: $event\ndata: $data\n\n" }
