// LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24), for the head plain `claude` runs on. Marcos, Oct
// 25: "claude here is still using a local account instead of the server account on [the server]". The goal: on a node,
// `claude` runs Claude Code on the server's Claude logins, as `claudex` runs on its ChatGPT login. A Claude head forwards
// its caller's own login, and devbox's serves every turn from logins it holds itself (its native sign-ins), so it
// has logins to offer; one whose logins include the caller's own has none it could serve a node with. A node's turn
// reaches Anthropic signed by the server's login, with the OAuth beta that login needs, and never carries a key of the
// server's or the node's placeholder. Driven as it is used: two real daemons, torad (sharing) and mac (the node), in one
// home of their own, so neither reads this machine's ~/.claude; Anthropic is a stand-in that records what it was sent;
// each turn is sent with the headers Claude Code 2.1.310 sent from a node's launch (captured Oct 25, 2026).
package splice.app.share

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
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

/** The word a node's launch plants as Claude Code's auth token: it opens nothing anywhere. */
private const val NODE_WORD = "splice-lan"

/** The beta Anthropic requires beside a Claude subscription's OAuth token. */
private const val OAUTH_BETA = "oauth-2025-04-20"

/** The beta flags Claude Code 2.1.310 sent from a node's launch, holding a token rather than a Claude sign-in. */
private const val NODE_BETAS = "claude-code-20250219,interleaved-thinking-2025-05-14,effort-2025-11-24"

/** The pool label of a caller's own Claude Code sign-in, forwarded rather than held. */
private const val CALLERS_OWN = "claude-code"

private const val OPUS = "claude-opus-5-5"
private const val HEAD = "claude-splice"

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
    fun `the server offers its Claude head, with the logins it holds itself`(@TempDir tmp: Path) = machines(tmp) { m ->
        val offer = m.offered().single { it.str("command") == HEAD }
        val logins = offer.getValue("logins").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("native:claude" in logins, "torad's own Claude sign-in is a login a node can run on: $logins")
        assertTrue(CALLERS_OWN !in logins, "no login is the caller's own, which a node has none of: $logins")
    }

    @Test
    fun `a node's turn reaches Anthropic on the server's login, with the OAuth beta, carrying no key`(
        @TempDir tmp: Path,
    ) = machines(tmp) { m ->
        val (status, body) = m.nodeTurn("node-session-1")
        assertEquals(200, status, body)
        assertTrue(body.contains("event: message_stop"), body)
        val sent = anthropic.requests.last()
        assertEquals("Bearer $TORAD_LOGIN", sent["authorization"], "the server's own login signs the turn")
        val betas = sent["anthropic-beta"].orEmpty().split(',').map(String::trim)
        assertTrue(OAUTH_BETA in betas, "a Claude login's token rides with the OAuth beta: $betas")
        assertTrue(NODE_BETAS.split(',').all { it in betas }, "the node's own betas still ride: $betas")
        val keys = listOf(m.toradTurnKey, m.toradMgmtKey, NODE_WORD)
        val leaked = sent.filterValues { value -> keys.any { it in value } }.keys
        assertTrue(leaked.isEmpty(), "no key of the server's, nor the node's placeholder, reaches Anthropic: $leaked")
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

    @Test
    fun `a node's claude launches on the server's Claude head, not on the node's own`(
        @TempDir tmp: Path,
    ) = machines(tmp) { m ->
        val (status, recipe) = m.launch("claude")
        assertEquals(200, status, recipe.toString())
        val env = recipe.getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals("${m.share}/$HEAD", env["ANTHROPIC_BASE_URL"], env.toString())
        assertEquals(NODE_WORD, env["ANTHROPIC_AUTH_TOKEN"])
        // Plain `claude` stays the person's own Claude Code: its settings, instructions and sessions in ~/.claude.
        assertFalse("CLAUDE_CONFIG_DIR" in env, "a wrapped claude reads the node's own ~/.claude: $env")
        // PR 423 review: and its models stay the person's to pick, as the server's own wrapped `claude` leaves them
        // (V4-449): no enforced allowlist over ~/.claude's, no tier slots, no window pinned to one model's.
        val argv = recipe.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
        val overlay = argv.getOrNull(argv.indexOf("--settings") + 1).orEmpty()
        assertTrue("statusLine" in overlay, "the launch carries its settings overlay: $argv")
        assertFalse("availableModels" in overlay, "the person's own ~/.claude picks the models: $overlay")
        val planted = env.keys.filter { it.startsWith("ANTHROPIC_DEFAULT_") || it.startsWith("CLAUDE_CODE_MAX_") } +
            env.keys.filter { it == "CLAUDE_CODE_AUTO_COMPACT_WINDOW" }
        assertEquals(emptyList<String>(), planted, "no model or window is planted over the person's own: $env")
        // PR 423 review round 2: its turns reach Anthropic through the server's Claude head, as a wrapped claude's do
        // through a head here, so auto mode keeps Anthropic's own checks. /login and /logout stay hidden, as on every
        // remote command: the node holds no login, and signing in here would change nothing about where turns go.
        assertFalse("CLAUDE_CODE_AUTO_MODE_SERVER" in env, "auto mode keeps Anthropic's checks: $env")
        assertEquals("1", env["DISABLE_LOGIN_COMMAND"], env.toString())
    }

    @Test
    fun `a Claude head whose logins include the caller's own is not offered`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val state = StatePaths(baseOverride = home.resolve(".splice/state"))
        // An added login and no native sign-in: the head's own login is then the caller's, which a node has none of.
        val added = Files.createDirectories(state.stateDir.resolve("claude-accounts/$HEAD/work"))
        Files.writeString(added.resolve(".credentials.json"), credentials("synthetic-work"))
        UserHome.within(home) {
            val ports = Ports()
            val config = Files.writeString(tmp.resolve("splice.toml"), toradToml(ports))
            val torad = daemon(toradToml(ports), state, config)
            runBlocking { torad.start() }
            try {
                awaitListening(ports.toradControl, ports.toradClaude, ports.share)
                val offered = offered("http://127.0.0.1:${ports.share}").map { it.str("command") }
                assertTrue(HEAD !in offered, "only a head that holds every login it serves on is offered: $offered")
            } finally {
                runBlocking { torad.stop() }
            }
        }
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
                body(Machines(ports, toradState, macState))
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

    private inner class Machines(val ports: Ports, toradState: StatePaths, macState: StatePaths) {
        val share = "http://127.0.0.1:${ports.share}"
        val toradMgmtKey = MgmtKey(toradState).get()
        val toradTurnKey = TurnKey(MgmtKey(toradState)).get()
        private val macKey = MgmtKey(macState).get()

        fun offered(): List<JsonObject> = offered(share)

        /** A turn as Claude Code sends one from a node's launch: to the server's address, holding the node's word. */
        fun nodeTurn(session: String): Pair<Int, String> = runBlocking {
            val response = client.post("$share/$HEAD/v1/messages?beta=true") {
                header("Authorization", "Bearer $NODE_WORD")
                header("anthropic-beta", NODE_BETAS)
                header("anthropic-version", "2023-06-01")
                header("anthropic-dangerous-direct-browser-access", "true")
                header("user-agent", "claude-cli/2.1.310 (external, cli)")
                header("x-app", "cli")
                header("X-Claude-Code-Session-Id", session)
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"$OPUS","stream":true,"max_tokens":64,""" +
                        """"messages":[{"role":"user","content":"hi from the mac"}]}""",
                )
            }
            response.status.value to response.bodyAsText()
        }

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

        /** Mac's launch of [command], as the launch shim asks for it: its status and its recipe or refusal. */
        fun launch(command: String): Pair<Int, JsonObject> = runBlocking {
            val response = client.post("http://127.0.0.1:${ports.macControl}/launch/$command") {
                header("Authorization", "Bearer $macKey")
                header("Content-Type", "application/json")
                setBody("""{"args":[]}""")
            }
            response.status.value to Json.parseToJsonElement(response.bodyAsText()).jsonObject
        }
    }

    private fun offered(share: String): List<JsonObject> = runBlocking {
        val info = Json.parseToJsonElement(client.get("$share/").bodyAsText()).jsonObject
        info.getValue("commands").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

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
        discovery_prefix = "claude-splice--"
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
