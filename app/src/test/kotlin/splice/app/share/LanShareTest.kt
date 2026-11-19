// LAN share (docs/specs/lan-share.md), what still holds since share-door-only (docs/specs/door-network.md, decision 5):
// a node's command whose box can't be reached is said in those words before Claude Code starts, and a request that does
// not name the box in its Host is refused. The tests that drove a command at an address of its own (its launch, turns,
// auth=, models=, statusline, -c, /login) are gone with that share; their goals are shown through the door. Driven as it
// is used: two real daemons, torad (sharing) and mac (the node), each in its own state, with torad's ChatGPT upstream
// mocked, and mac's launch answered through its control port as the launch shim asks it.
package splice.app.share

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.core.topology.AuthKind
import splice.head.MockChatGptUpstream
import splice.oauth.OAuthAccountFiles
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport

/** What torad's two logins sign upstream requests with. */
private const val PRIMARY_TOKEN = "synthetic-primary"
private const val WORK_TOKEN = "synthetic-work"

/** The model the node's statusline and turns name, as torad's claudex lists it. */
private const val SOL = "gpt-5.6-sol"
private const val MINI = "gpt-5.6-mini"

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
    private lateinit var torad: Daemon
    private lateinit var mac: Daemon
    private lateinit var macState: StatePaths
    private lateinit var macConfig: String
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
        torad = Daemon(
            topology = TopologyLoader.parse(toradToml(primary)),
            statePaths = StatePaths(baseOverride = tmp.resolve("torad-state")),
            log = {},
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        runBlocking { torad.start() }
        awaitListening(toradControl, toradClaudex, sharePort)

        val macAuth = tmp.resolve("mac-codex.json")
        Files.writeString(macAuth, """{"tokens":{"access_token":"mac-own","account_id":"mac-id"}}""")
        macState = StatePaths(baseOverride = tmp.resolve("mac-state"))
        macConfig = macToml(macAuth)
        macKey = MgmtKey(macState).get()
        mac = Daemon(
            topology = TopologyLoader.parse(macConfig),
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

        [remote.offline]
        url = "http://127.0.0.1:$closedPort/claudex"

        [remote.nowhere]
        url = "http://no-such-host.invalid:3097/claudex"
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

    private fun JsonObject.error(): String = getValue("error").jsonPrimitive.content

    @Test
    fun `a server that can't be reached is said before Claude Code starts`() {
        val (status, refusal) = launch("offline")
        assertEquals(409, status, refusal.toString())
        val said = refusal.error()
        assertTrue(said.contains("offline runs on 127.0.0.1"), said)
        assertTrue(said.contains("http://127.0.0.1:$closedPort"), said)
        assertTrue(said.contains("can't be reached"), said)
        assertTrue(said.contains("nothing is listening on 127.0.0.1:$closedPort"), said)

        val (unresolved, nowhere) = launch("nowhere")
        assertEquals(409, unresolved, nowhere.toString())
        assertTrue(nowhere.error().contains("no-such-host.invalid does not resolve on this network"), nowhere.error())
    }

    @Test
    fun `a request that does not name the server in its Host is refused`() = runBlocking {
        val response = client.get("$share/") { header("Host", "torad.attacker.example") }
        assertEquals(403, response.status.value, response.bodyAsText())
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
