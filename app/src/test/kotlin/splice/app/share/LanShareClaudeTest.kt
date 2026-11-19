// The door on the network (docs/specs/door-network.md), for Claude's models, between two real machines. The goal: a
// node's `claude` on a box's door runs Claude's models on the Claude accounts added to splice there, as it runs any
// other model on the box's logins. A node's turn reaches Anthropic signed by the box's added account, with the OAuth
// beta that login needs, carrying no key of either machine's and no launch proof the node sent. A box whose Claude head
// holds only the person's own sign-in serves its Claude models to no other machine: they are not in the door's
// launch, and a node's turn naming one is refused in words Claude Code prints, with nothing sent to Anthropic. A
// session on the box itself, signed in to Claude, names the OAuth beta once. Driven as it is used: two real daemons,
// torad (sharing) and mac (the node), in one home of their own, so neither reads this machine's ~/.claude; Anthropic
// is a stand-in that records what it was sent; mac's launch answered through its control port as the launch shim asks
// it, and each turn sent the way Claude Code's Mantle dialect sends it, to the door the launch planted.
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.LAUNCH_PROOF_HEADER
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.config.UserHome
import splice.core.testing.TestPorts
import splice.share.DOOR_SEGMENT
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport

/** The token torad's own Claude sign-in (~/.claude) signs Anthropic requests with. */
private const val TORAD_LOGIN = "synthetic-torad-login"

/** The token of the Claude account added to splice on torad, which a door turn runs on. */
private const val ADDED_LOGIN = "synthetic-added-login"

/** The word a node's launch plants as its Mantle bearer: it opens nothing anywhere. */
private const val NODE_WORD = "splice-lan"

/** A launch proof the node makes up: the box's head must never see it as the box's own. */
private const val GUESSED_PROOF = "guessed-by-the-node"

/** The beta Anthropic requires beside a Claude subscription's OAuth token. */
private const val OAUTH_BETA = "oauth-2025-04-20"

private const val OPUS = "claude-opus-5-5"
private const val HEAD = "claude"

// why: the door opens once torad's heads are up; a loaded machine takes a while to start them.
private const val DOOR_OPEN_MS = 60_000L

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
    fun `a node's turn on the door reaches Anthropic on the box's added account, with the OAuth beta, carrying no key`(
        @TempDir tmp: Path,
    ) = machines(tmp, added = true) { m ->
        val rows = m.rows()
        val claude = rows.firstOrNull { it.str("command") == HEAD }?.str("model")
        assertTrue(claude != null, "the box's door serves its Claude models on the added account: $rows")
        val (status, body) = m.nodeTurn(checkNotNull(claude), "node-session-1")
        assertEquals(200, status, body)
        assertTrue(body.contains("event: message_stop"), body)
        val sent = anthropic.requests.last()
        assertEquals("Bearer $ADDED_LOGIN", sent["authorization"], "the box's added account signs the turn")
        val betas = sent["anthropic-beta"].orEmpty().split(',').map(String::trim)
        assertTrue(OAUTH_BETA in betas, "a Claude login's token rides with the OAuth beta: $betas")
        val keys = listOf(m.toradTurnKey, m.toradMgmtKey, m.macKey, NODE_WORD, GUESSED_PROOF, TORAD_LOGIN)
        val leaked = sent.filterValues { value -> keys.any { it in value } }.keys
        assertTrue(leaked.isEmpty(), "no key of either machine, nor what the node sent, reaches Anthropic: $leaked")
    }

    @Test
    fun `a box whose Claude head holds only the person's own sign-in serves its Claude models to no other machine`(
        @TempDir tmp: Path,
    ) = machines(tmp, added = false) { m ->
        val rows = m.rows()
        assertTrue(rows.none { it.str("command") == HEAD }, "the person's own sign-in is not the door's: $rows")
        val before = anthropic.requests.size
        val (status, body) = m.nodeTurn(OPUS, "node-session-2")
        assertNotEquals(200, status, body)
        val said = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject.str("message")
        assertTrue(said.isNotBlank(), body)
        assertEquals(before, anthropic.requests.size, "a refused turn reached Anthropic")
    }

    @Test
    fun `a session on the server itself, signed in to Claude, names the OAuth beta once`(
        @TempDir tmp: Path,
    ) = machines(tmp, added = false) { m ->
        val (status, body) = m.serverTurn("$OAUTH_BETA,claude-code-20250219")
        assertEquals(200, status, body)
        val sent = anthropic.requests.last()
        assertEquals("Bearer $TORAD_LOGIN", sent["authorization"])
        val betas = sent["anthropic-beta"].orEmpty().split(',').map(String::trim)
        assertEquals(listOf(OAUTH_BETA, "claude-code-20250219"), betas, "the session's own flags, each once")
    }

    /** Both machines, started in [tmp]'s own home: torad signed in to Claude there, with a Claude account [added] to
     *  splice beside that sign-in or none, and mac with a Claude head of its own beside `boxdoor`, torad's door.
     *  Stopped before the home is given back. */
    private fun machines(tmp: Path, added: Boolean, body: (Machines) -> Unit) {
        val home = Files.createDirectories(tmp.resolve("home"))
        Files.createDirectories(home.resolve(".claude"))
        Files.writeString(home.resolve(".claude/.credentials.json"), credentials(TORAD_LOGIN))
        val ports = Ports()
        // torad runs as splice does on a machine: its state under the home its Claude sign-in is in, its config a file.
        val toradState = StatePaths(baseOverride = home.resolve(".splice/state"))
        if (added) {
            val account = Files.createDirectories(toradState.stateDir.resolve("claude-accounts/$HEAD/work"))
            Files.writeString(account.resolve(".credentials.json"), credentials(ADDED_LOGIN))
        }
        val toradConfig = Files.createDirectories(tmp.resolve("torad-config")).resolve("splice.toml")
        Files.writeString(toradConfig, toradToml(ports))
        val macState = StatePaths(baseOverride = tmp.resolve("mac-state"))
        UserHome.within(home) {
            val torad = daemon(toradToml(ports), toradState, toradConfig)
            val mac = daemon(macToml(ports), macState)
            runBlocking {
                torad.start()
                mac.start()
            }
            try {
                awaitListening(ports.toradControl, ports.toradClaude, ports.share, ports.macControl, ports.macClaude)
                val machines = Machines(ports, toradState, macState)
                machines.awaitDoor()
                body(machines)
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
        val macKey = MgmtKey(macState).get()

        /** The rows torad's door launches another machine's `claude` with. */
        fun rows(): List<JsonObject> = runBlocking {
            val launch = Json.parseToJsonElement(client.get("$share/$DOOR_SEGMENT/launch").bodyAsText()).jsonObject
            launch["rows"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        }

        fun awaitDoor() {
            val deadline = System.currentTimeMillis() + DOOR_OPEN_MS
            while (doorStatus() != 200) {
                check(System.currentTimeMillis() < deadline) { "torad's door did not open" }
                LockSupport.parkNanos(50_000_000L)
            }
        }

        private fun doorStatus(): Int? = runBlocking {
            runCatching { client.get("$share/$DOOR_SEGMENT/launch").status.value }.getOrNull()
        }

        /** A turn as Claude Code's Mantle dialect sends one from mac's launch of `boxdoor`: to the door and with the
         *  bearer that launch planted, naming [model], and with a launch proof the node made up. */
        fun nodeTurn(model: String, session: String): Pair<Int, String> = runBlocking {
            val launched = client.post("http://127.0.0.1:${ports.macControl}/launch/boxdoor") {
                header("Authorization", "Bearer $macKey")
                header("Content-Type", "application/json")
                setBody("""{"args":[]}""")
            }
            val recipe = Json.parseToJsonElement(launched.bodyAsText()).jsonObject
            check(launched.status.value == 200) { "mac's launch of boxdoor: $recipe" }
            val env = recipe.getValue("env").jsonObject
            val response = client.post("${env.str("ANTHROPIC_BEDROCK_MANTLE_BASE_URL")}/v1/messages?beta=true") {
                header("Authorization", "Bearer ${env.str("AWS_BEARER_TOKEN_BEDROCK")}")
                header("anthropic-version", "2023-06-01")
                header("anthropic-beta", "claude-code-20250219")
                header(LAUNCH_PROOF_HEADER, GUESSED_PROOF)
                header("X-Claude-Code-Session-Id", session)
                header("Content-Type", "application/json")
                setBody(
                    """{"model":"$model","stream":true,"max_tokens":64,""" +
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

        [remote.boxdoor]
        url = "http://127.0.0.1:${ports.share}/$DOOR_SEGMENT"
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
