// The door on the network (docs/specs/door-network.md), between two real machines. The goal: a node's command on a
// box's door runs Claude Code pointed at that door and holding nothing else. Its launch hands Claude Code the door's
// address and the fixed node word, never a credential of either machine's; its turns answer on the box's own login;
// a box that can't be reached, or whose door is still starting, is said in those words before Claude Code starts; a
// request a web page can send runs no turn, and one that does not name the box in its Host is refused; and an entry
// left from the per-command days (share-door-only, Nov 19, 2026) still boots the node, its launch refused with the
// add that replaces it. Driven as it is used: two real daemons, torad (sharing) and mac (the node), each in its own
// state, with torad's ChatGPT upstream mocked; mac's launch answered through its control port as the launch shim asks
// it, and each turn sent the way Claude Code's Mantle dialect sends it, to the door and with the bearer the launch
// planted.
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.AuthKind
import splice.head.MockChatGptUpstream
import splice.oauth.OAuthAccountFiles
import splice.share.DOOR_SEGMENT
import splice.share.DOOR_STARTING
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.exists

/** What torad's two logins sign upstream requests with. */
private const val PRIMARY_TOKEN = "synthetic-primary"
private const val WORK_TOKEN = "synthetic-work"

/** The word a node's launch plants as its Mantle bearer: it opens nothing anywhere. */
private const val NODE_TOKEN = "splice-lan"

/** What mac's own login signs its own head's requests with. */
private const val MAC_TOKEN = "mac-own"

private const val SOL = "gpt-5.6-sol"
private const val MINI = "gpt-5.6-mini"

// why: the door opens once torad's heads are up; a loaded machine takes a while to start them.
private const val DOOR_OPEN_MS = 60_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LanShareTest {
    private val upstream = MockChatGptUpstream()
    private val macUpstream = MockChatGptUpstream()
    private val client = HttpClient(CIO)
    private val toradControl = TestPorts.reserve()
    private val toradClaudex = TestPorts.reserve()
    private val sharePort = TestPorts.reserve()
    private val macControl = TestPorts.reserve()
    private val macLocal = TestPorts.reserve()
    private val closedPort = TestPorts.reserve()

    /** A box whose door is still starting: it answers its launch 503, in the words a starting door says. */
    private val starting = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = """{"type":"error","error":{"type":"api_error",""" +
                """"message":"splice share: devbox's door $DOOR_STARTING"}}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(503, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        start()
    }
    private lateinit var torad: Daemon
    private lateinit var mac: Daemon
    private lateinit var toradState: StatePaths
    private lateinit var macState: StatePaths
    private lateinit var macKey: String
    private val share get() = "http://127.0.0.1:$sharePort"

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val primary = tmp.resolve("torad-codex.json")
        Files.writeString(primary, """{"tokens":{"access_token":"$PRIMARY_TOKEN","account_id":"primary-id"}}""")
        OAuthAccountFiles().writeLabeled(
            AuthKind.ChatgptOAuth,
            primary,
            "work",
            Json.parseToJsonElement("""{"tokens":{"access_token":"$WORK_TOKEN","account_id":"work-id"}}""").jsonObject,
        )
        toradState = StatePaths(baseOverride = tmp.resolve("torad-state"))
        torad = Daemon(
            topology = TopologyLoader.parse(toradToml(primary)),
            statePaths = toradState,
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { torad.start() }
        awaitListening(toradControl, toradClaudex, sharePort)
        awaitDoor()

        val macAuth = tmp.resolve("mac-codex.json")
        Files.writeString(macAuth, """{"tokens":{"access_token":"$MAC_TOKEN","account_id":"mac-id"}}""")
        macState = StatePaths(baseOverride = tmp.resolve("mac-state"))
        macKey = MgmtKey(macState).get()
        mac = Daemon(
            topology = TopologyLoader.parse(macToml(macAuth)),
            statePaths = macState,
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { mac.start() }
        awaitListening(macControl, macLocal)
    }

    private fun toradToml(primary: Path): String = """
        [daemon]
        control_port = $toradControl

        [share]
        enabled = true
        port = $sharePort

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${upstream.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "${primary.esc()}" }
        quirks = { account_id_header = true, cache_key = "first-message-hash", summary_field = true }
        [[providers.codex.models]]
        id = "$SOL"
        context_window = 272000
        [[providers.codex.models]]
        id = "$MINI"
        context_window = 128000

        [heads.claudex]
        provider = "codex"
        port = $toradClaudex
        discovery_prefix = "claude-codex--"
        pinned_model = "$SOL"
    """.trimIndent()

    // mac's entries: torad's door as `torad` (what `splice remote add <torad> --as torad` writes), a door that can't be
    // reached, a door still starting, and the per-command address a node kept from before share-door-only.
    private fun macToml(macAuth: Path): String = """
        [daemon]
        control_port = $macControl

        [providers.codex]
        dialect = "openai-responses"
        base_url = "${macUpstream.baseUrl}"
        auth = { kind = "chatgpt-oauth", file = "${macAuth.esc()}" }
        [[providers.codex.models]]
        id = "$SOL"
        context_window = 272000

        [heads.local]
        provider = "codex"
        port = $macLocal
        discovery_prefix = "claude-local--"
        pinned_model = "$SOL"

        [remote.torad]
        url = "$share/$DOOR_SEGMENT"

        [remote.offline]
        url = "http://127.0.0.1:$closedPort/$DOOR_SEGMENT"

        [remote.nowhere]
        url = "http://no-such-host.invalid:3097/$DOOR_SEGMENT"

        [remote.starting]
        url = "http://127.0.0.1:${starting.address.port}/$DOOR_SEGMENT"

        [remote.claudex]
        url = "$share/claudex"
    """.trimIndent()

    @AfterAll
    fun tearDown() {
        runBlocking {
            mac.stop()
            torad.stop()
        }
        client.close()
        upstream.stop()
        macUpstream.stop()
        starting.stop(0)
    }

    /** Mac's launch of [command], as the launch shim asks for it: its status and its recipe or refusal. */
    private fun launch(command: String): Pair<Int, JsonObject> = runBlocking {
        val response = client.post("http://127.0.0.1:$macControl/launch/$command") {
            header("Authorization", "Bearer $macKey")
            header("Content-Type", "application/json")
            setBody(buildJsonObject { put("args", buildJsonArray {}) }.toString())
        }
        response.status.value to Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.env(): Map<String, String> =
        getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun JsonObject.error(): String = getValue("error").jsonPrimitive.content

    /** A turn as Claude Code's Mantle dialect sends one from the session [recipe] launched: to its door, with its
     *  bearer and the model it starts on, plus any [extra] headers. */
    private fun turn(recipe: JsonObject, session: String, extra: Map<String, String> = emptyMap()): Pair<Int, String> =
        runBlocking {
            val env = recipe.env()
            val response = client.post("${env.getValue("ANTHROPIC_BEDROCK_MANTLE_BASE_URL")}/v1/messages?beta=true") {
                header("Authorization", "Bearer ${env.getValue("AWS_BEARER_TOKEN_BEDROCK")}")
                header("Content-Type", "application/json")
                header("anthropic-version", "2023-06-01")
                header("X-Claude-Code-Session-Id", session)
                extra.forEach { (name, value) -> header(name, value) }
                setBody(
                    """{"model":"${env.getValue("ANTHROPIC_MODEL")}","stream":true,"max_tokens":64,""" +
                        """"messages":[{"role":"user","content":"hi from the mac"}]}""",
                )
            }
            response.status.value to response.bodyAsText()
        }

    @Test
    fun `a node's command launches Claude Code on the box's door, holding no credential`() {
        val (status, recipe) = launch("torad")
        assertEquals(200, status, recipe.toString())
        val env = recipe.env()
        assertEquals("1", env["CLAUDE_CODE_USE_MANTLE"], env.toString())
        assertEquals("$share/$DOOR_SEGMENT", env["ANTHROPIC_BEDROCK_MANTLE_BASE_URL"])
        assertEquals(NODE_TOKEN, env["AWS_BEARER_TOKEN_BEDROCK"])
        val unset = recipe.getValue("unset").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(unset.containsAll(listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")), unset.toString())
        val secrets = listOf(
            macKey,
            TurnKey(MgmtKey(macState)).get(),
            MgmtKey(toradState).get(),
            TurnKey(MgmtKey(toradState)).get(),
            PRIMARY_TOKEN,
            WORK_TOKEN,
            MAC_TOKEN,
        )
        val held = recipe.toString()
        assertTrue(secrets.none { it in held }, "a node's launch holds no credential of either machine")
        assertFalse(macState.stateDir.resolve("keys.toml").exists(), "a node keeps no key")
    }

    @Test
    fun `a turn the node's Claude Code sends through the door answers on the box's login`() {
        val (_, recipe) = launch("torad")
        val before = upstream.upstreamAuths.size
        val (status, body) = turn(recipe, "mac-session-1", mapOf("x-api-key" to MAC_TOKEN))
        assertEquals(200, status, body)
        assertTrue(body.contains("event: message_stop"), body)
        val sent = upstream.upstreamAuths.drop(before).map { it.second.orEmpty() }
        assertEquals(1, sent.size, sent.toString())
        assertTrue(sent.single() in listOf("Bearer $PRIMARY_TOKEN", "Bearer $WORK_TOKEN"), sent.toString())
        assertTrue(sent.none { NODE_TOKEN in it || MAC_TOKEN in it }, "the node's auth went upstream: $sent")
    }

    @Test
    fun `a box that can't be reached, or whose door is still starting, is said before Claude Code starts`() {
        val (status, refusal) = launch("offline")
        assertEquals(409, status, refusal.toString())
        val said = refusal.error()
        assertTrue(said.contains("offline runs on 127.0.0.1's door"), said)
        assertTrue(said.contains("can't be reached"), said)
        assertTrue(said.contains("nothing is listening on 127.0.0.1:$closedPort"), said)

        val (unresolved, nowhere) = launch("nowhere")
        assertEquals(409, unresolved, nowhere.toString())
        assertTrue(nowhere.error().contains("no-such-host.invalid does not resolve on this network"), nowhere.error())

        val (opening, wait) = launch("starting")
        assertEquals(409, opening, wait.toString())
        assertTrue(wait.error().contains(DOOR_STARTING), wait.error())
    }

    @Test
    fun `a request a web page can send runs no turn on the box's logins`() = runBlocking {
        val before = upstream.upstreamAuths.size
        val body = """{"model":"$SOL","max_tokens":64,"messages":[{"role":"user","content":"from a page"}]}"""
        val fromPage = client.post("$share/$DOOR_SEGMENT/v1/messages") {
            header("Origin", "https://page.example")
            header("Content-Type", "application/json")
            setBody(body)
        }
        assertEquals(403, fromPage.status.value, fromPage.bodyAsText())
        val notJson = client.post("$share/$DOOR_SEGMENT/v1/messages") {
            header("Content-Type", "text/plain")
            header("X-Claude-Code-Session-Id", "page-session")
            setBody(body)
        }
        assertEquals(400, notJson.status.value, notJson.bodyAsText())
        assertEquals(before, upstream.upstreamAuths.size, "a page's request reached the box's login")
    }

    @Test
    fun `a request that does not name the box in its Host is refused`() = runBlocking {
        for (path in listOf("/", "/$DOOR_SEGMENT/launch")) {
            val response = client.get("$share$path") { header("Host", "torad.attacker.example") }
            assertEquals(403, response.status.value, "$path: ${response.bodyAsText()}")
        }
    }

    @Test
    fun `an entry from the per-command days still boots the node, and its launch says what replaces it`() {
        val (status, refusal) = launch("claudex")
        assertEquals(409, status, refusal.toString())
        val said = refusal.error()
        assertTrue("no longer shares one command at an address of its own" in said, said)
        assertTrue("splice remote add http://127.0.0.1:$sharePort" in said, said)
    }

    /** Waits until torad's door has opened: it answers its launch once its heads are up. */
    private fun awaitDoor() {
        val deadline = System.currentTimeMillis() + DOOR_OPEN_MS
        while (doorStatus() != 200) {
            check(System.currentTimeMillis() < deadline) { "torad's door did not open" }
            LockSupport.parkNanos(50_000_000L)
        }
    }

    private fun doorStatus(): Int? = runBlocking {
        runCatching { client.get("$share/$DOOR_SEGMENT/launch").status.value }.getOrNull()
    }

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

private fun Path.esc() = toString().replace("\\", "/")
