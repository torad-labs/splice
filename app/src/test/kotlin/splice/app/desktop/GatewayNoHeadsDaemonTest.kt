// gateway-no-heads (.git/campaign/gateway-no-heads.json): one real daemon with no `[heads.*]` block at all serves
// every provider in splice.toml through the gateway, in all three upstream formats. A turn names a model by its
// selector, `claude-<provider>--<id>` or an old prefix a caller already holds, and reaches that provider alone: as its
// own model id, in its own format, under its own credential and no other (never the caller's door key, never another
// provider's). A provider that refuses a turn, or is not there, is answered honestly, and the daemon goes on serving.
package splice.app.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.FileHeldKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class GatewayNoHeadsDaemonTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val controlPort = TestPorts.reserve()
    private val deadPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()

    private val alpha = FormatProvider(Format.MESSAGES, "alpha-1", "alpha-ok")
    private val xai = FormatProvider(Format.CHAT, "grok-fast", "xai-ok")
    private val gamma = FormatProvider(Format.RESPONSES, "gamma-1", "gamma-ok")
    private val providers = listOf(alpha, xai, gamma)

    @TempDir
    lateinit var tmp: Path

    private val statePaths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }
    private val terminal by lazy { FileHeldKey(statePaths.terminalKeyFile, "splice-terminal-", "claude", "", {}).get() }

    private val keys = mapOf(
        "alpha" to "alpha-secret-key",
        "xai" to "xai-secret-key",
        "gamma" to "gamma-secret-key",
        "down" to "down-secret-key",
    )

    /** A turn of each format, by the selector that names it: the current one, an old head prefix the gateway owns, and
     *  one a person aliased in `[door.selector_aliases]`. */
    private val cases = listOf(
        Case("claude-alpha--alpha-1", alpha, "alpha-1", "alpha-ok"),
        Case("claude-xai--grok-fast", xai, "grok-fast", "xai-ok"),
        Case("claude-grok--grok-fast", xai, "grok-fast", "xai-ok"),
        Case("claude-gamma--gamma-1", gamma, "gamma-1", "gamma-ok"),
        Case("claude-old--gamma-1", gamma, "gamma-1", "gamma-ok"),
    )

    @Test
    fun `every format answers a streamed and a whole turn by its selector, current or old, with no head`() =
        runBlocking {
            try {
                running(toml(heads = false)) {
                    for (case in cases) {
                        for (stream in listOf(false, true)) turn(case, stream)
                    }
                }
            } finally {
                stop()
            }
        }

    @Test
    fun `the door lists each model once under a selector that names its provider, with or without a head block`() =
        runBlocking {
            try {
                val without = running(toml(heads = false)) { listed() }
                assertEquals(listOf("claude-alpha--alpha-1", "claude-xai--grok-fast", "claude-gamma--gamma-1"), without)
                val with = running(toml(heads = true)) { listed() }
                assertEquals(without, with, "a head block leaves the gateway's list as it was")
            } finally {
                stop()
            }
        }

    @Test
    fun `a Code Mode provider stays on the gateway with a same-key legacy head`() = runBlocking {
        try {
            val config = responsesWithCodeMode()
            val without = running(config) { listed() }
            assertEquals(listOf("claude-gamma--gamma-1"), without)
            val legacy = """
                [heads.gamma]
                provider = "gamma"
                port = $headPort
                discovery_prefix = "claude-gamma--"
                pinned_model = "gamma-1"
                claude = { command = "claude-gamma" }
            """.trimIndent()
            running("$config\n$legacy\n") {
                assertEquals(without, listed())
                val response = post("claude-gamma--gamma-1", stream = true)
                val body = response.bodyAsText()
                assertEquals(200, response.status.value, body)
                assertTrue("gamma-ok" in body, body)
                assertCredentials(gamma, gamma.turns.last())
            }
        } finally {
            stop()
        }
    }

    @Test
    fun `a provider that refuses a turn is answered honestly, and the others still serve`() = runBlocking {
        try {
            running(toml(heads = false)) {
                alpha.refusing = 400 to """{"type":"error","error":{"type":"invalid_request_error","message":"no"}}"""
                val refused = post(cases.first().selector, stream = false)
                assertEquals(400, refused.status.value, refused.bodyAsText())
                val error = json.parseToJsonElement(refused.bodyAsText()).jsonObject
                assertEquals("error", error.str("type"))
                assertEquals("invalid_request_error", error.getValue("error").jsonObject.str("type"))
                assertTrue(error.getValue("error").jsonObject.str("message").isNotBlank())
                alpha.refusing = null
                turn(cases[1], stream = false)
                assertEquals(200, client.get("http://127.0.0.1:$controlPort/health").status.value)
            }
        } finally {
            stop()
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    fun `a provider that is not there stays listed, and a turn to it is an honest error, the daemon still up`() =
        runBlocking {
            try {
                running(toml(heads = false, down = true)) {
                    assertTrue("claude-down--down-1" in listed(), "a provider whose endpoint is down is still listed")
                    val lost = post("claude-down--down-1", stream = false)
                    assertTrue(lost.status.value >= 400, lost.bodyAsText())
                    val said = json.parseToJsonElement(lost.bodyAsText()).jsonObject.getValue("error").jsonObject
                    assertTrue(said.str("message").isNotBlank(), lost.bodyAsText())
                    turn(cases.first(), stream = false)
                }
            } finally {
                stop()
            }
        }

    /** One turn of [case]: it reaches that provider alone, as its model, under its key and nobody's else. */
    private suspend fun turn(case: Case, stream: Boolean) {
        val before = providers.associateWith { it.turns.size }
        val response = post(case.selector, stream)
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, "${case.selector} stream=$stream: $body")
        assertTrue(case.says in body, "${case.selector} stream=$stream: $body")
        if (stream) assertTrue("message_start" in body && "message_stop" in body, body) else usage(body)
        for (provider in providers) {
            val sent = provider.turns.size - before.getValue(provider)
            assertEquals(if (provider === case.provider) 1 else 0, sent, "${case.selector} reached the wrong provider")
        }
        val seen = case.provider.turns.last()
        assertTrue("\"model\":\"${case.upstream}\"" in seen.body.replace(" ", ""), "its own model id: ${seen.body}")
        assertCredentials(case.provider, seen)
    }

    /** A provider is called with its own key, and nothing of the gateway's or of another provider's reaches it. */
    private fun assertCredentials(provider: FormatProvider, seen: Seen) {
        val own = keys.entries.first { (name, _) -> providerOf(name) === provider }
        assertTrue(own.value in seen.everything, "${own.key} is called with its own key")
        assertFalse(terminal in seen.everything, "the caller's door key never reaches a provider")
        for ((name, key) in keys) if (name != own.key) assertFalse(key in seen.everything, "$name key reached it")
    }

    private fun providerOf(name: String): FormatProvider = when (name) {
        "alpha" -> alpha
        "xai" -> xai
        else -> gamma
    }

    /** A whole answer's usage: counted, and the cache write said as its five-minute and hour parts. */
    private fun usage(body: String) {
        val usage = json.parseToJsonElement(body).jsonObject.getValue("usage").jsonObject
        assertTrue(usage.getValue("input_tokens").jsonPrimitive.content.toLong() > 0, body)
        val parts = usage.getValue("cache_creation").jsonObject
        assertEquals(setOf("ephemeral_5m_input_tokens", "ephemeral_1h_input_tokens"), parts.keys)
    }

    private suspend fun post(selector: String, stream: Boolean): HttpResponse =
        client.post("http://127.0.0.1:$controlPort/door/v1/messages") {
            header("Authorization", "Bearer $terminal")
            header("Content-Type", "application/json")
            header("x-claude-code-session-id", "gateway-no-heads")
            setBody(
                """{"model":"$selector","max_tokens":64,"stream":$stream,"thinking":{"type":"adaptive"},""" +
                    """"output_config":{"effort":"max"},"messages":[{"role":"user","content":"hello"}]}""",
            )
        }

    /** The model ids the door lists, in order. */
    private suspend fun listed(): List<String> {
        val response = client.get("http://127.0.0.1:$controlPort/door/v1/models") {
            header("Authorization", "Bearer $terminal")
        }
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        return body.getValue("data").jsonArray.map { it.jsonObject.str("id") }
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

    private fun stop() {
        providers.forEach(FormatProvider::stop)
        client.close()
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    /** A legacy head left in splice.toml: the gateway serves the same models, and never relays a turn to it. */
    private fun headBlock() = "[heads.claude-grok]\nprovider = \"xai\"\nport = $headPort\n" +
        "discovery_prefix = \"claude-grok--\"\npinned_model = \"grok-fast\"\nclaude = { command = \"claude-grok\" }"

    private fun responsesWithCodeMode(): String {
        val auth = tmp.resolve("gamma-oauth.json")
        Files.writeString(auth, """{"tokens":{"access_token":"${keys.getValue("gamma")}","account_id":"fixture"}}""")
        return """
            [daemon]
            control_port = $controlPort
            [providers.gamma]
            dialect = "openai-responses"
            base_url = "${gamma.baseUrl}"
            auth = { kind = "chatgpt-oauth", file = "${auth.toString().replace("\\", "/")}" }
            quirks = { code_mode = true }
            [[providers.gamma.models]]
            id = "gamma-1"
            context_window = 200000
        """.trimIndent()
    }

    private fun toml(heads: Boolean, down: Boolean = false): String {
        val files = keys.mapValues { (name, key) ->
            tmp.resolve("$name.json").also { Files.writeString(it, """{"api_key":"$key"}""") }.toString()
        }
        return buildString {
            appendLine("[daemon]\ncontrol_port = $controlPort\n")
            appendLine(provider("alpha", "anthropic-passthrough", alpha.baseUrl, files, "alpha-1"))
            appendLine(provider("xai", "openai-chat", xai.baseUrl, files, "grok-fast") + XAI_EFFORT)
            appendLine(provider("gamma", "openai-responses", gamma.baseUrl, files, "gamma-1"))
            if (down) appendLine(provider("down", "openai-chat", "http://127.0.0.1:$deadPort/v1", files, "down-1"))
            appendLine("[door.selector_aliases]\n\"claude-old--\" = \"gamma\"")
            if (heads) appendLine(headBlock())
        }
    }

    private fun provider(name: String, dialect: String, base: String, files: Map<String, String>, model: String) = """
        [providers.$name]
        dialect = "$dialect"
        base_url = "$base"
        auth = { kind = "api-key", file = "${files.getValue(name).replace("\\", "/")}" }
        [[providers.$name.models]]
        id = "$model"
        context_window = 200000
    """.trimIndent()
}

/** One turn to make: its selector, the provider it must reach, the id it runs as there, and what it says. */
private data class Case(val selector: String, val provider: FormatProvider, val upstream: String, val says: String)

// What the xai chat provider's reasoning effort reaches its server as.
private const val XAI_EFFORT = "\n[providers.xai.quirks.chat_effort_vocabulary]\ndefault = \"high\"\n" +
    "[providers.xai.quirks.chat_effort_vocabulary.levels]\nlow = \"low\"\nhigh = \"high\"\nmax = \"max\""
