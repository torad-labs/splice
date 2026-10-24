// LAN share (docs/specs/lan-share.md, Marcos's ruling of 2026-10-24). The goal: a command another machine's splice
// serves runs, on this machine, Claude Code pointed at that machine and holding nothing else. Its launch hands Claude
// Code the server's address and no credential; its turns answer on the server's login, the one the address names with
// `auth=`; it is shown the models the address names with `models=`; its statusline is the server's; its /login says
// where the logins live; and a server that can't be reached, or refuses, is said in those words before Claude Code
// starts. Driven as it is used: two real daemons, torad (sharing) and mac (the node), each in its own state, with
// torad's ChatGPT upstream mocked; mac's launch answered through its control port as the launch shim asks it, and
// each turn sent the way Claude Code sends it, to the base URL and with the auth token the launch planted.
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import splice.topology.TopologyLoader
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

/** What torad's two logins sign upstream requests with. */
private const val PRIMARY_TOKEN = "synthetic-primary"
private const val WORK_TOKEN = "synthetic-work"

/** The word a node's launch plants as Claude Code's auth token: it opens nothing anywhere. */
private const val NODE_TOKEN = "splice-lan"

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

        [remote.claudex]
        url = "$share/claudex"

        [remote.claudex-work]
        url = "$share/claudex?auth=work"

        [remote.claudex-mini]
        url = "$share/claudex?models=$MINI"

        [remote.claudex-nobody]
        url = "$share/claudex?auth=nobody"

        [remote.claudex-nomodel]
        url = "$share/claudex?models=gpt-0"

        [remote.grok]
        url = "$share/grok"

        [remote.offline]
        url = "http://127.0.0.1:$closedPort/claudex"
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
        }
        response.status.value to Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.env(): Map<String, String> =
        getValue("env").jsonObject.mapValues { it.value.jsonPrimitive.content }

    private fun JsonObject.error(): String = getValue("error").jsonPrimitive.content

    /** A turn as Claude Code sends one from the session [recipe] launched: to its base URL, with its token. */
    private fun turn(recipe: JsonObject, session: String): Pair<Int, String> = runBlocking {
        val env = recipe.env()
        val response = client.post("${env.getValue("ANTHROPIC_BASE_URL")}/v1/messages") {
            header("Authorization", "Bearer ${env.getValue("ANTHROPIC_AUTH_TOKEN")}")
            header("Content-Type", "application/json")
            header("X-Claude-Code-Session-Id", session)
            setBody(
                """{"model":"${env.getValue("ANTHROPIC_DEFAULT_OPUS_MODEL")}","stream":true,"max_tokens":64,""" +
                    """"system":"SCENARIO:basic","messages":[{"role":"user","content":"hi from the mac"}]}""",
            )
        }
        response.status.value to response.bodyAsText()
    }

    /** The models a launch shows the session: every tier Claude Code is planted. */
    private fun tierModels(env: Map<String, String>): Set<String> =
        env.filterKeys { Regex("ANTHROPIC_DEFAULT_[A-Z]+_MODEL").matches(it) }.values.toSet()

    @Test
    fun `a node's command launches Claude Code pointed at the server, holding no credential`() {
        val (status, recipe) = launch("claudex")
        assertEquals(200, status, recipe.toString())
        val env = recipe.env()
        assertEquals("$share/claudex", env["ANTHROPIC_BASE_URL"])
        assertEquals(NODE_TOKEN, env["ANTHROPIC_AUTH_TOKEN"])
        val unset = recipe.getValue("unset").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(unset.containsAll(listOf("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")), unset.toString())
        val secrets = listOf(macKey, TurnKey(MgmtKey(macState)).get(), PRIMARY_TOKEN, WORK_TOKEN)
        assertTrue(env.values.none { value -> secrets.any { it in value } }, "a node's session holds no credential")
        assertTrue(tierModels(env).all { it.contains(SOL) || it.contains(MINI) }, env.toString())
    }

    @Test
    fun `a turn the node's Claude Code sends answers on the server's login`() {
        val (_, recipe) = launch("claudex")
        val before = upstream.upstreamAuths.size
        val (status, body) = turn(recipe, "mac-session-1")
        assertEquals(200, status, body)
        assertTrue(body.contains("event: message_stop"), body)
        assertEquals("Bearer $PRIMARY_TOKEN", upstream.upstreamAuths.drop(before).single().second)
    }

    @Test
    fun `the address's auth picks the server login the node's sessions run on`() {
        val (status, recipe) = launch("claudex-work")
        assertEquals(200, status, recipe.toString())
        val before = upstream.upstreamAuths.size
        val (turned, body) = turn(recipe, "mac-session-2")
        assertEquals(200, turned, body)
        assertEquals("Bearer $WORK_TOKEN", upstream.upstreamAuths.drop(before).single().second)
    }

    @Test
    fun `the address's models are the only ones the node is shown`() {
        val (status, recipe) = launch("claudex-mini")
        assertEquals(200, status, recipe.toString())
        val env = recipe.env()
        assertTrue(tierModels(env).isNotEmpty() && tierModels(env).all { it.contains(MINI) }, env.toString())
        val settings = Path.of(env.getValue("CLAUDE_CONFIG_DIR")).resolve("settings.json").readText()
        assertFalse(settings.contains(SOL), settings)
    }

    @Test
    fun `a server that can't be reached is said before Claude Code starts`() {
        val (status, refusal) = launch("offline")
        assertEquals(409, status, refusal.toString())
        val said = refusal.error()
        assertTrue(said.contains("offline runs on 127.0.0.1"), said)
        assertTrue(said.contains("http://127.0.0.1:$closedPort"), said)
        assertTrue(said.contains("can't be reached"), said)
    }

    @Test
    fun `an address naming a login or a model the server lacks is refused with what it has`() {
        val (status, refusal) = launch("claudex-nobody")
        assertEquals(409, status, refusal.toString())
        assertTrue(refusal.error().contains("nobody") && refusal.error().contains("work"), refusal.error())
        val (modelStatus, modelRefusal) = launch("claudex-nomodel")
        assertEquals(409, modelStatus, modelRefusal.toString())
        assertTrue(modelRefusal.error().contains("gpt-0") && modelRefusal.error().contains(MINI), modelRefusal.error())
    }

    @Test
    fun `a command the server does not offer is refused with the ones it does`() {
        val (status, refusal) = launch("grok")
        assertEquals(409, status, refusal.toString())
        assertTrue(refusal.error().contains("grok") && refusal.error().contains("claudex"), refusal.error())
    }

    @Test
    fun `the server lists what it offers`() = runBlocking {
        val listed = Json.parseToJsonElement(client.get("$share/").bodyAsText()).jsonObject
        val commands = listed.getValue("commands").jsonArray.map { it.jsonObject }
        val claudex = commands.single { it.getValue("head").jsonPrimitive.content == "claudex" }
        val logins = claudex.getValue("logins").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("work" in logins, logins.toString())
        assertEquals(setOf(SOL, MINI), claudex.getValue("models").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }

    @Test
    fun `a request that does not name the server in its Host is refused`() = runBlocking {
        val response = client.get("$share/") { header("Host", "torad.attacker.example") }
        assertEquals(403, response.status.value, response.bodyAsText())
    }

    @Test
    fun `the node's statusline is the server's`() {
        val (_, recipe) = launch("claudex")
        val tree = Path.of(recipe.env().getValue("CLAUDE_CONFIG_DIR"))
        val settings = Json.parseToJsonElement(tree.resolve("settings.json").readText()).jsonObject
        val command = settings.getValue("statusLine").jsonObject.getValue("command").jsonPrimitive.content
        assertTrue(command.contains("$share/claudex/statusline"), command)
        val (code, said) = statusline(command)
        assertEquals(200, code, said)
    }

    @Test
    fun `the node's login says its logins live on the server`() {
        val (_, recipe) = launch("claudex")
        val tree = Path.of(recipe.env().getValue("CLAUDE_CONFIG_DIR"))
        val texts = tree.walk().filter { it.isRegularFile() }.map { it.readText() }.toList()
        assertTrue(texts.any { it.contains("claudex signs in on") }, "no /login text names the server")
        assertTrue(texts.none { it.contains("signs in with an API key") })
    }

    /** The statusline command run as Claude Code runs it, by bash with the session's JSON on stdin: the answer's status
     *  (curl's write-out, appended to the same command) and its text. */
    private fun statusline(command: String): Pair<Int, String> {
        val driven = "$command --write-out '\\n%{http_code}'"
        val process = ProcessBuilder("bash", "-c", driven).start()
        process.outputStream.use {
            it.write("""{"session_id":"mac-session-1","model":{"id":"$SOL","display_name":"$SOL"}}""".toByteArray())
        }
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(10, TimeUnit.SECONDS)) { "statusline command did not exit within 10s: $driven" }
        return out.substringAfterLast('\n').trim().toInt() to out.substringBeforeLast('\n')
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
