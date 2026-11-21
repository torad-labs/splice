// gateway-no-heads: the keyed team door on a real daemon with no head at all (docs/specs/server-mode.md, and
// .git/campaign/gateway-no-heads.json). A team member's key opens `/door/v1/...` on the server's listener; the turn is
// served by the provider its model names, and what is the server's holds on that provider, however the client wrote the
// selector: a person whose allowlist names `alpha` is served on alpha and refused, with a 403 that names the provider and
// nothing sent to it, on beta, whether they asked beta's model by its own selector or by an old prefix the gateway reads
// as beta's. Without a key nothing is answered.
package splice.app.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import splice.app.cli.server.ServerCommand
import splice.app.desktop.Format
import splice.app.desktop.FormatProvider
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeamDoorDaemonTest {
    private val control = TestPorts.reserve()
    private val listen = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val alpha = FormatProvider(Format.MESSAGES, "alpha-1", "alpha-ok")
    private val beta = FormatProvider(Format.CHAT, "beta-1", "beta-ok")
    private lateinit var daemon: Daemon
    private lateinit var everything: String
    private lateinit var alphaOnly: String

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val toml = toml(tmp)
        val config = Files.writeString(tmp.resolve("splice.toml"), toml)
        val state = tmp.resolve("state")
        daemon = Daemon(
            topology = TopologyLoader.parse(toml),
            statePaths = StatePaths(baseOverride = state),
            log = {},
            topologyPath = config,
        )
        runBlocking { daemon.start() }
        awaitListening(control, listen)
        val vars = mapOf("SPLICE_CONFIG" to "$config", "SPLICE_STATE_DIR" to "$state", "HOME" to "$tmp")
        everything = serve(vars, listOf("key", "create", "--person", "ola@example.com"))
        alphaOnly = serve(vars, listOf("key", "create", "--person", "kim@example.com"))
        serve(vars, listOf("person", "set", "kim@example.com", "--commands", "alpha"))
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        alpha.stop()
        beta.stop()
    }

    /** One `splice server ...` verb on this daemon's state: its first line, which for `key create` is the key. */
    private fun serve(vars: Map<String, String>, args: List<String>): String {
        val printed = StringBuilder()
        val said = ServerCommand(TerminalOutput { printed.appendLine(it) }, TerminalOutput { }, EnvReader { vars[it] })
            .run(args)
        assertEquals(0, said, printed.toString())
        return printed.lines().first()
    }

    private fun door(path: String, body: String?, key: String?): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$listen/door/v1/$path"))
            .method(if (body == null) "GET" else "POST", publisher)
            .header("Content-Type", "application/json")
        key?.let { request.header("Authorization", "Bearer $it") }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun turn(selector: String, key: String?): HttpResponse<String> =
        door("messages", """{"model":"$selector","max_tokens":32,"messages":[{"role":"user","content":"hi"}]}""", key)

    private fun models(key: String): List<String> =
        json.parseToJsonElement(door("models", null, key).body()).jsonObject.getValue("data").jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test
    fun `a person with no allowlist is served on every provider the server serves, with no head`() {
        val onAlpha = turn("claude-alpha--alpha-1", everything)
        assertEquals(200, onAlpha.statusCode(), onAlpha.body())
        assertTrue("alpha-ok" in onAlpha.body(), onAlpha.body())
        val onBeta = turn("claude-beta--beta-1", everything)
        assertEquals(200, onBeta.statusCode(), onBeta.body())
        assertTrue("beta-ok" in onBeta.body(), onBeta.body())
        assertEquals(listOf("claude-alpha--alpha-1", "claude-beta--beta-1"), models(everything))
    }

    @Test
    fun `a person's allowlist holds on the provider the selector names, an old prefix included, and reaches nothing`() {
        val sent = beta.turns.size
        val allowed = turn("claude-alpha--alpha-1", alphaOnly)
        assertEquals(200, allowed.statusCode(), allowed.body())
        for (selector in listOf("claude-beta--beta-1", "zeta--beta-1")) {
            val refused = turn(selector, alphaOnly)
            assertEquals(403, refused.statusCode(), refused.body())
            assertTrue("beta" in refused.body(), "the refusal names the provider: ${refused.body()}")
        }
        assertEquals(sent, beta.turns.size, "a refused provider is sent nothing")
        assertEquals(listOf("claude-alpha--alpha-1"), models(alphaOnly))
    }

    @Test
    fun `a turn carries the team key to no provider, and each provider is called with its own`() {
        val before = alpha.turns.size
        assertEquals(200, turn("claude-alpha--alpha-1", everything).statusCode())
        val seen = alpha.turns[before]
        assertTrue("alpha-secret-key" in seen.everything, "alpha is called with its own key")
        assertFalse(everything in seen.everything, "the team key goes no further")
        assertFalse("beta-secret-key" in seen.everything)
    }

    @Test
    fun `without a key, or with one the server does not know, nothing is answered`() {
        assertEquals(401, turn("claude-alpha--alpha-1", null).statusCode())
        assertEquals(401, turn("claude-alpha--alpha-1", "not-a-key").statusCode())
        assertEquals(401, door("models", null, null).statusCode())
    }

    private fun toml(tmp: Path): String {
        val keys = mapOf("alpha" to "alpha-secret-key", "beta" to "beta-secret-key").mapValues { (name, key) ->
            tmp.resolve("$name.json").also { Files.writeString(it, """{"api_key":"$key"}""") }
        }
        return """
            [daemon]
            control_port = $control

            [server]
            listen = "127.0.0.1:$listen"
            commands = ["alpha", "beta"]

            [door.selector_aliases]
            "zeta--" = "beta"

            [providers.alpha]
            dialect = "anthropic-passthrough"
            base_url = "${alpha.baseUrl}"
            auth = { kind = "api-key", file = "${keys.getValue("alpha")}" }
            [[providers.alpha.models]]
            id = "alpha-1"
            context_window = 200000

            [providers.beta]
            dialect = "openai-chat"
            base_url = "${beta.baseUrl}"
            auth = { kind = "api-key", file = "${keys.getValue("beta")}" }
            [[providers.beta.models]]
            id = "beta-1"
            context_window = 200000
        """.trimIndent() + "\n"
    }
}
