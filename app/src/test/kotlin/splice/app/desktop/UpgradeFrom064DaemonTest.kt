// upgrade-compat (Nov 23, 2026): one real daemon on a splice.toml written for 0.6.4, whose `[heads.realinfer]` holds the
// provider's tiers, standing prompt and selector prefix (as a real laptop's did, which refused every 0.7.0 boot). The
// file boots, and the door serves the provider as the head did: a selector under the head's old prefix runs there, and
// a bare Claude id of each tier the head maps (opus, sonnet, haiku) runs as the model the head names for it, with the
// head's standing prompt on the turn. A tier no model_slots maps is still refused by name (Nov 17).
package splice.app.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.FileHeldKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.head.awaitListening
import splice.topology.ConfigFindings
import splice.topology.ConfigRead
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

/** The head's standing prompt, which the gateway's turns on the provider carry. */
private const val PROMPT = "Answer as the realinfer team's reviewer."

class UpgradeFrom064DaemonTest {
    private val client = HttpClient(CIO)
    private val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val controlPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()
    private val realinfer = FormatProvider(Format.CHAT, "glm-5.3", "realinfer-ok")

    @TempDir
    lateinit var tmp: Path

    private val statePaths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }
    private val terminal by lazy { FileHeldKey(statePaths.terminalKeyFile, "splice-terminal-", "claude", "", {}).get() }

    @Test
    fun `a 0 6 4 head's tiers, prompt and prefix serve on the door, and an unmapped tier is still refused`() =
        runBlocking {
            val toml = toml()
            assertTrue(ConfigFindings.read(toml, tmp) is ConfigRead.Ready, "the 0.6.4 file boots on 0.7.0")
            try {
                running(toml) {
                    assertEquals("glm-5.3", turn("realinfer--glm-5.3"), "the head's old prefix")
                    assertTrue(PROMPT in realinfer.turns.last().body, "the head's prompt rides the gateway's turn")
                    assertEquals("glm-5.3", turn("claude-opus-5-5"), "opus as model_slots names it")
                    assertEquals("glm-5.3", turn("claude-sonnet-5-5"), "sonnet as model_slots names it")
                    assertEquals("glm-5.3-flash", turn("claude-haiku-4-5"), "haiku as model_slots names it")
                    val before = realinfer.turns.size
                    val unmapped = post("claude-fable-5")
                    assertTrue(unmapped.status.value >= 400, "fable is mapped by no slot: ${unmapped.bodyAsText()}")
                    assertEquals(before, realinfer.turns.size, "an unmapped tier never reaches the provider")
                }
            } finally {
                realinfer.stop()
                client.close()
            }
        }

    /** One whole turn named [model] in the door's one session, answered 200: the model id the provider was sent. */
    private suspend fun turn(model: String): String {
        val response = post(model)
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, "$model: $body")
        assertTrue("realinfer-ok" in body, "$model: $body")
        val sent = realinfer.turns.last().body.replace(" ", "")
        return Regex("\"model\":\"([^\"]+)\"").find(sent)?.groupValues?.get(1).orEmpty()
    }

    private suspend fun post(model: String): HttpResponse =
        client.post("http://127.0.0.1:$controlPort/door/v1/messages") {
            header("Authorization", "Bearer $terminal")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "upgrade-from-064")
            setBody(
                """{"model":"$model","max_tokens":64,"stream":false,""" +
                    """"messages":[{"role":"user","content":"hello"}]}""",
            )
        }

    private suspend fun <T> running(toml: String, checks: suspend () -> T): T {
        val daemon = Daemon(
            topology = TopologyLoader.parse(toml),
            statePaths = statePaths,
            log = { logged.add(it) },
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        daemon.start()
        runCatching { awaitListening(controlPort) }
            .getOrElse { throw AssertionError("${it.message}; daemon log:\n${logged.joinToString("")}", it) }
        try {
            return checks()
        } finally {
            daemon.stop()
        }
    }

    /** A 0.6.4 file: the provider says its models only, and its one head says the rest. */
    private fun toml(): String {
        val key = tmp.resolve("realinfer.json").also { Files.writeString(it, """{"api_key":"realinfer-secret"}""") }
        return """
            [daemon]
            control_port = $controlPort

            [providers.realinfer]
            dialect = "openai-chat"
            base_url = "${realinfer.baseUrl}"
            auth = { kind = "api-key", file = "${key.toString().replace("\\", "/")}" }

            [[providers.realinfer.models]]
            id = "glm-5.3"
            context_window = 200000

            [[providers.realinfer.models]]
            id = "glm-5.3-flash"
            context_window = 200000

            [heads.realinfer]
            provider = "realinfer"
            port = $headPort
            discovery_prefix = "realinfer--"
            pinned_model = "glm-5.3"
            context_window = 180000
            model_slots = { opus = "glm-5.3", sonnet = "glm-5.3", haiku = "glm-5.3-flash" }
            system_prompt = "$PROMPT"

            [heads.realinfer.claude]
            command = "claude-realinfer"
        """.trimIndent() + "\n"
    }
}
