// Key rotation (Oct 26, 2026), end to end through a real daemon. A key that was shown somewhere it should not have
// been is replaced with `splice rotate-key` while a session launched on a gateway head runs, and the session must not
// be stranded. It holds no key of its own: its settings carry an apiKeyHelper that prints the turn key from the 0600
// header file. How Claude Code reacts is read from its 2.1.310 bundle, never from a session: on a 401 whose key
// source is apiKeyHelper, with no ANTHROPIC_AUTH_TOKEN set, its retry decision clears the helper's cache and retries,
// and the retry rebuilds the client, which runs the helper again and sends the same request again. This test plays
// that client: the helper through `sh -c`, the same turn before the rotation, after it with the old key, and after
// the helper's next run.
package splice.app

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.cli.auth.RotateKeyCommand
import splice.client.mcp.McpAccessKey
import splice.core.auth.RefreshAttempt
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.Cancellables
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// why: what a person typed, carried in the turn Claude Code sends again after the helper's next run
private const val DRAFT = "the draft that must survive the rotation"

private const val TURN_BODY = """{"model":"claude-relay--relay-model","max_tokens":16,""" +
    """"messages":[{"role":"user","content":"$DRAFT"}],"stream":true}"""

private const val FORWARD_BODY = """{"model":"claude-forward--forward-model","max_tokens":16,""" +
    """"messages":[{"role":"user","content":"$DRAFT"}],"stream":true}"""

// why: the person's own Anthropic login, which a head that forwards the caller's credential exists to send on
private const val OWN_LOGIN = "sk-ant-oat01-the-persons-own-login"

// why: the words of the head's refusal of one of splice's own keys, which say what to unset
private const val OWN_KEY_REFUSAL = "are not upstream credentials"

private const val TURN_CALL = "POST /v1/messages"

// The pages an older `splice console` and the removed `splice dashboard` left in the state folder.
private val OLD_PAGES = listOf("console-open.html", "dashboard-open.html")

class KeyRotationDaemonTest {

    @Test
    fun `a rotated key strands no session that reads its turn key through its key helper`(@TempDir tmp: Path) {
        UserHome.within(tmp) {
            runBlocking {
                val rig = Rig(tmp)
                try {
                    rig.start()
                    rotateUnderARunningSession(rig)
                } finally {
                    rig.stop()
                }
            }
        }
    }

    // `splice sessions relaunch` asks the daemon to relaunch each session that holds a planted key, one at a time. Only
    // the management key opens that, and a session the daemon does not see running is refused, so nothing is started.
    @Test
    fun `the relaunch route takes the management key and refuses a session that is not running`(@TempDir tmp: Path) {
        UserHome.within(tmp) {
            runBlocking {
                val rig = Rig(tmp)
                try {
                    rig.start()
                    assertEquals(401, rig.relaunch("no-such-session", "not-the-management-key").status)
                    val answer = rig.relaunch("no-such-session", rig.managementKey())
                    assertEquals(404, answer.status, answer.text)
                    assertTrue("no running session" in answer.text, answer.text)
                } finally {
                    rig.stop()
                }
            }
        }
    }

    // A session launched before the key helper still holds the turn key a rotation retired, and a launch from inside it
    // onto a head that forwards the person's own Anthropic login carries that key unless something drops it. The head
    // still knows it as splice's: it is refused there, with the words that say what to unset, and never sent to the
    // vendor, which answers it 401 while Claude Code sends it again until its caller gives up (Oct 13). The person's own
    // login still rides, so the head is seen forwarding.
    @Test
    fun `a key the rotation retired is refused as splice's own on a head that forwards the caller's login`(
        @TempDir tmp: Path,
    ) {
        UserHome.within(tmp) {
            runBlocking {
                val rig = Rig(tmp, forwarding = true)
                try {
                    rig.start()
                    val oldMgmt = rig.managementKey()
                    val oldTurn = shellOutput(launchWithHelper(rig, oldMgmt))
                    val said = mutableListOf<String>()
                    val exit = rig.rotateKey(said)
                    val turnKey = rig.forwardedTurn(oldTurn)
                    val managementKey = rig.forwardedTurn(oldMgmt)
                    val own = rig.forwardedTurn(OWN_LOGIN)
                    assertAll(
                        { assertEquals(0, exit, "splice rotate-key: $said") },
                        { assertEquals(401, turnKey.status, "the retired turn key: ${turnKey.text}") },
                        { assertTrue(OWN_KEY_REFUSAL in turnKey.text, turnKey.text) },
                        {
                            assertEquals(401, managementKey.status, "the retired management key: ${managementKey.text}")
                        },
                        { assertTrue(OWN_KEY_REFUSAL in managementKey.text, managementKey.text) },
                        { assertEquals(200, own.status, "the person's own login rides: ${own.text}") },
                        {
                            assertEquals(listOf("Bearer $OWN_LOGIN"), rig.upstream.credentials) {
                                "only the person's own login reached the vendor"
                            }
                        },
                    )
                } finally {
                    rig.stop()
                }
            }
        }
    }

    private suspend fun rotateUnderARunningSession(rig: Rig) {
        val oldMgmt = rig.managementKey()
        val helper = launchWithHelper(rig, oldMgmt)
        val before = shellOutput(helper)
        val first = rig.turn(before)
        assertEquals(200, first.status, "the helper's key runs a turn: ${first.text}")
        val mcpBefore = rig.adoptMcpAccess(oldMgmt)
        rig.writeOldConsolePages(oldMgmt)

        val said = mutableListOf<String>()
        val exit = rig.rotateKey(said)
        val mcpAfter = rig.publishedMcpAccess()
        val pagesLeft = rig.consolePagesLeft()
        val newMgmt = rig.managementKey()
        val oldControl = rig.control(oldMgmt)
        val newControl = rig.control(newMgmt)
        val refused = rig.turn(before)
        val reachedBeforeRetry = rig.upstream.turns.size
        val after = shellOutput(helper)
        val retried = rig.turn(after)

        assertAll(
            { assertEquals(0, exit, "splice rotate-key: $said") },
            { assertTrue(oldMgmt != newMgmt, "the key on disk is a new one") },
            { assertEquals(401, oldControl, "the old management key is refused") },
            { assertEquals(200, newControl, "the new management key is accepted") },
            { assertEquals(401, refused.status, "the session's next request is refused, which reruns its helper") },
            {
                assertEquals(1, reachedBeforeRetry) {
                    "a refused turn never reaches the head's upstream: ${rig.upstream.calls}"
                }
            },
            { assertTrue(before != after, "the helper's next run hands the session the new turn key") },
            { assertEquals(200, retried.status, "the same turn goes through on the same head: ${retried.text}") },
            { assertTrue("message_stop" in retried.text, "the retried turn streamed to its end: ${retried.text}") },
            {
                assertTrue(
                    rig.upstream.turns.size == 2 && rig.upstream.turns.all { DRAFT in it },
                    "the turn before the rotation and the one after it both reached the upstream with the draft: " +
                        rig.upstream.calls,
                )
            },
            { assertTrue(mcpBefore != mcpAfter, "the MCP access key is revoked and replaced") },
            { assertEquals(emptyList<String>(), pagesLeft, "no page sends a browser to an address with a key") },
            { assertTrue(said.isNotEmpty(), "rotate-key says what it did") },
            { assertPrintsNoKey(said, listOf(oldMgmt, newMgmt, before, after, mcpBefore, mcpAfter)) },
        )
    }

    /** The launch of a session on the gateway head: it is planted no key, and its settings name the key helper. */
    private suspend fun launchWithHelper(rig: Rig, mgmt: String): String {
        val launched = rig.launch(mgmt)
        assertFalse("ANTHROPIC_AUTH_TOKEN" in launched.env, "the session is planted no key: ${launched.env.keys}")
        assertTrue("ANTHROPIC_AUTH_TOKEN" in launched.unset, "an outer session's planted key is scrubbed")
        return rig.keyHelper()
    }

    /** No line `splice rotate-key` printed holds any of [secrets]; a failure shows the lines with each one masked. */
    private fun assertPrintsNoKey(said: List<String>, secrets: List<String>) {
        val masked = said.map { line -> secrets.fold(line) { text, key -> text.replace(key, "<a key>") } }
        assertTrue(said == masked) { "rotate-key prints no key: $masked" }
    }

    /** What Claude Code reads from its key helper: the command's output through a shell, trimmed. */
    private fun shellOutput(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "key helper did not exit within 10s: $command" }
        return out
    }
}

/** The recipe a launch answered, as the launch shim reads it. */
private data class Launched(val env: Map<String, String>, val unset: List<String>)

/** One answer from the head: its status and the whole streamed body. */
private data class Answer(val status: Int, val text: String)

/** One daemon with one gateway head that checks the turn key (an Anthropic passthrough on an API key held by splice),
 *  its recording upstream, and the files a person's machine holds beside it. With [forwarding], a second head on the
 *  same upstream holds no credential and forwards the caller's own login. */
private class Rig(private val tmp: Path, private val forwarding: Boolean = false) {
    val upstream = RecordingAnthropic()
    private val controlPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()
    private val forwardPort by lazy { TestPorts.reserve() }
    private val statePaths = StatePaths(baseOverride = tmp.resolve("state"))

    // the materializer writes only into a dir named like an isolated Claude Code config dir
    private val configDir = tmp.resolve(".claude-relay")
    private val toml = tmp.resolve("splice.toml")
    private val client = HttpClient(CIO)
    private val logs = CopyOnWriteArrayList<String>()
    private lateinit var daemon: Daemon

    suspend fun start() {
        val apiKey = tmp.resolve("relay.json")
        Files.writeString(apiKey, """{"api_key":"relay-daemon-key"}""")
        Files.writeString(
            toml,
            """
            [daemon]
            control_port = $controlPort

            [providers.relay]
            dialect = "anthropic-passthrough"
            base_url = "${upstream.baseUrl}"
            auth = { kind = "api-key", file = "${apiKey.esc()}" }
            [[providers.relay.models]]
            id = "relay-model"
            context_window = 128000

            [heads.relay]
            provider = "relay"
            port = $headPort
            discovery_prefix = "claude-relay--"
            pinned_model = "relay-model"
            claude = { command = "claude-relay", config_dir = "${configDir.esc()}" }
            """.trimIndent() + if (forwarding) "\n\n" + forwardingHead() else "",
        )
        daemon = Daemon(
            topology = TopologyLoader.parse(Files.readString(toml)),
            statePaths = statePaths,
            log = logs::add,
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        daemon.start()
        if (forwarding) awaitListening(controlPort, headPort, forwardPort) else awaitListening(controlPort, headPort)
    }

    /** A head that holds no credential of its own and forwards the caller's (auth kind "client"). */
    private fun forwardingHead(): String =
        """
        [providers.forward]
        dialect = "anthropic-passthrough"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "client" }
        [[providers.forward.models]]
        id = "forward-model"
        context_window = 128000

        [heads.forward]
        provider = "forward"
        port = $forwardPort
        discovery_prefix = "claude-forward--"
        pinned_model = "forward-model"
        """.trimIndent()

    suspend fun stop() {
        if (::daemon.isInitialized) daemon.stop()
        client.close()
        upstream.stop()
    }

    fun managementKey(): String = Files.readString(statePaths.mgmtKeyFile).trim()

    suspend fun launch(mgmt: String): Launched {
        val reply = client.post("http://127.0.0.1:$controlPort/launch/relay") {
            header("Authorization", "Bearer $mgmt")
            header("Content-Type", "application/json")
            setBody("{}")
        }
        val text = reply.bodyAsText()
        assertEquals(200, reply.status.value) { "$text\n" + logs.joinToString("") }
        val recipe = Json.parseToJsonElement(text).jsonObject
        return Launched(
            recipe.getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content },
            recipe.getValue("unset").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    fun keyHelper(): String {
        val settings = Json.parseToJsonElement(Files.readString(configDir.resolve("settings.json"))).jsonObject
        return checkNotNull(settings["apiKeyHelper"]?.jsonPrimitive?.content) { "the launch wrote no apiKeyHelper" }
    }

    /** A turn as Claude Code sends it with its helper's key, which it puts in both of these headers. */
    suspend fun turn(key: String): Answer {
        val reply = client.post("http://127.0.0.1:$headPort/v1/messages") {
            header("Authorization", "Bearer $key")
            header("x-api-key", key)
            header("Content-Type", "application/json")
            setBody(TURN_BODY)
        }
        return Answer(reply.status.value, reply.bodyAsText())
    }

    /** A turn on the head that forwards the caller's login, as Claude Code sends it with [credential] in
     *  ANTHROPIC_AUTH_TOKEN. */
    suspend fun forwardedTurn(credential: String): Answer {
        val reply = client.post("http://127.0.0.1:$forwardPort/v1/messages") {
            header("Authorization", "Bearer $credential")
            header("anthropic-version", "2023-06-01")
            header("Content-Type", "application/json")
            setBody(FORWARD_BODY)
        }
        return Answer(reply.status.value, reply.bodyAsText())
    }

    /** The daemon's relaunch of [session], as `splice sessions relaunch` asks for it with [key]. */
    suspend fun relaunch(session: String, key: String): Answer {
        val reply = client.post("http://127.0.0.1:$controlPort/api/sessions/$session/relaunch") {
            header("Authorization", "Bearer $key")
            setBody("{}")
        }
        return Answer(reply.status.value, reply.bodyAsText())
    }

    /** A management route's status for [mgmt]: the restart status, which reads and changes nothing. */
    suspend fun control(mgmt: String): Int =
        client.get("http://127.0.0.1:$controlPort/api/daemon/restart") { header("Authorization", "Bearer $mgmt") }
            .also { it.bodyAsText() }.status.value

    /** The MCP access key a session's hosted servers were handed before the rotation, adopted under [mgmt]. */
    fun adoptMcpAccess(mgmt: String): String =
        McpAccessKey(statePaths.stateDir, statePaths.mgmtKeyFile, source = { mgmt }).invoke()

    fun publishedMcpAccess(): String = Files.readString(statePaths.stateDir.resolve("mcp-hub/access-key")).trim()

    /** The pages as an older splice left them, each sending the browser to an address that carries [mgmt]. */
    fun writeOldConsolePages(mgmt: String) {
        OLD_PAGES.forEach { name ->
            val target = "http://127.0.0.1:$controlPort/#k=$mgmt"
            val page = """<meta http-equiv="refresh" content="0;url=$target">"""
            Files.writeString(statePaths.stateDir.resolve(name), page)
        }
    }

    fun consolePagesLeft(): List<String> = OLD_PAGES.filter { Files.exists(statePaths.stateDir.resolve(it)) }

    /** `splice rotate-key` as a person runs it, pointed at this daemon and its state folder alone. */
    fun rotateKey(said: MutableList<String>): Int {
        val env = mapOf(
            "SPLICE_CONFIG" to toml.toString(),
            "SPLICE_STATE_DIR" to statePaths.stateDir.toString(),
            "SPLICE_CONTROL_PORT" to controlPort.toString(),
            "HOME" to tmp.toString(),
        )
        return RotateKeyCommand(
            env = { name -> env[name] },
            out = TerminalOutput { said.add(it) },
            daemon = RotateKeyCommand.DaemonUp { port -> port == controlPort },
        ).run()
    }
}

/** An Anthropic Messages upstream that answers every call as a streamed turn and keeps each call it received. */
private class RecordingAnthropic {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val received = CopyOnWriteArrayList<Received>()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    /** The turns that reached it, each body in order. A call the daemon makes on its own, such as its model list, is
     *  not a turn. */
    val turns: List<String> get() = received.filter { it.call == TURN_CALL }.map { it.body }

    /** The Authorization each turn reached it with: whose credential the vendor was sent. */
    val credentials: List<String> get() = received.filter { it.call == TURN_CALL }.map { it.authorization }

    /** Each call as "METHOD path", in order: what a failed count names. */
    val calls: List<String> get() = received.map { it.call }

    private data class Received(val call: String, val body: String, val authorization: String)

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
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val authorization = ex.requestHeaders.getFirst("Authorization").orEmpty()
        received.add(Received("${ex.requestMethod} ${ex.requestURI.path}", body, authorization))
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

private fun Path.esc() = toString().replace("\\", "/")
