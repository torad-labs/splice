// Server mode's OpenAI route on a real daemon (docs/specs/server-mode.md, F4, openai-route). splice.toml names a
// provider that speaks openai-chat and serves its command; an OpenAI-style client with a person's key asks that
// command for its models and for completions, over the server's listener. What the provider's server is asked, and
// what the client reads of a stream, are the chat dialect's own here, wired the way the daemon wires them.
package splice.app.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ServerOpenAiDaemonTest {
    private val control = TestPorts.reserve()
    private val listen = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private lateinit var daemon: Daemon
    private lateinit var key: String

    /** One request as the provider's server got it. */
    private data class Asked(val path: String, val headers: Map<String, String>, val body: String)

    private val asked = CopyOnWriteArrayList<Asked>()

    /** The provider's server: it sends its usage frame only when the request asked for it, as OpenAI's API does. */
    private val box = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1") { exchange -> exchange.use(::answer) }
        start()
    }

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.use { String(it.readBytes()) }
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
        asked += Asked(exchange.requestURI.path, headers, body)
        val sent = body.takeIf(String::isNotEmpty)?.let { Json.parseToJsonElement(it).jsonObject }
        val streamed = sent?.get("stream") == JsonPrimitive(true)
        val usage = sent?.get("stream_options")?.jsonObject?.get("include_usage") == JsonPrimitive(true)
        val answer = when {
            exchange.requestURI.path == "/v1/models" -> MODELS
            streamed -> listOfNotNull(WORD, USAGE.takeIf { usage }, DONE).joinToString("") { "$it\n\n" }
            else -> COMPLETION
        }.toByteArray()
        exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, answer.size.toLong())
        exchange.responseBody.write(answer)
    }

    @BeforeAll
    fun setUp(@TempDir tmp: Path) {
        val keyFile = Files.writeString(tmp.resolve("box.key"), BOX_KEY)
        val toml = """
            [daemon]
            control_port = $control

            [server]
            listen = "127.0.0.1:$listen"
            commands = ["claude-team"]

            [providers.box]
            dialect = "openai-chat"
            base_url = "http://127.0.0.1:${box.address.port}/v1"
            local = false
            auth = { kind = "api-key", file = "$keyFile" }
            extra_headers = { x-box-team = "blue" }
            forward_headers = ["x-team-tag"]

            [[providers.box.models]]
            id = "team-model"
            label = "team-model"
            context_window = 131072

            [heads.box]
            provider = "box"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-box--"
            pinned_model = "team-model"
            [heads.box.claude]
            command = "claude-team"
        """.trimIndent() + "\n"
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
        val printed = StringBuilder()
        val made = ServerCommand(TerminalOutput { printed.appendLine(it) }, TerminalOutput { }, EnvReader { vars[it] })
            .run(listOf("key", "create", "--person", "ola@example.com"))
        assertEquals(0, made, printed.toString())
        key = printed.lines().first()
    }

    @AfterAll
    fun tearDown() {
        runBlocking { daemon.stop() }
        box.stop(0)
    }

    private fun request(
        path: String,
        body: String?,
        bearer: String? = key,
        own: Map<String, String> = emptyMap(),
    ): HttpRequest {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$listen/claude-team/v1/$path"))
            .method(if (body == null) "GET" else "POST", publisher)
            .header("Content-Type", "application/json")
        bearer?.let { request.header("Authorization", "Bearer $it") }
        own.forEach { (name, value) -> request.header(name, value) }
        return request.build()
    }

    /** The `data:` lines a streamed completion asked with [options] as its `stream_options` answers with, and the
     *  request the provider's server got for it. */
    private fun streamed(options: String?): Pair<List<String>, Asked> {
        val extra = options?.let { ""","stream_options":$it""" }.orEmpty()
        val body = """{"model":"team-model","stream":true,"messages":[{"role":"user","content":"hi"}]$extra}"""
        val before = asked.size
        val answer = http.send(request("chat/completions", body), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, answer.statusCode(), answer.body())
        assertTrue(answer.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"))
        return answer.body().lines().filter { it.startsWith("data:") } to asked[before]
    }

    private fun usageAsked(asked: Asked): Boolean {
        val options = Json.parseToJsonElement(asked.body).jsonObject.getValue("stream_options").jsonObject
        return options["include_usage"] == JsonPrimitive(true)
    }

    @Test
    fun `a streamed completion always asks the server for usage, and only a client that asked reads the usage frame`() {
        val (plain, plainAsked) = streamed(options = null)
        assertEquals(listOf(WORD, DONE), plain)
        assertTrue(usageAsked(plainAsked))

        val (declined, declinedAsked) = streamed("""{"include_usage":false}""")
        assertEquals(listOf(WORD, DONE), declined)
        assertTrue(usageAsked(declinedAsked))

        val (wanted, wantedAsked) = streamed("""{"include_usage":true}""")
        assertEquals(listOf(WORD, USAGE, DONE), wanted)
        assertTrue(usageAsked(wantedAsked))
    }

    @Test
    fun `a completion goes to the provider's server as it was sent, under the provider's key and headers`() {
        val sent = """{ "model":"team-model",  "messages":[{"role":"user","content":"olá"}], "seed": 7 }"""
        val before = asked.size
        val answer = http.send(request("chat/completions", sent), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, answer.statusCode(), answer.body())
        assertEquals(COMPLETION, answer.body())
        val got = asked[before]
        assertEquals("/v1/chat/completions", got.path)
        assertEquals(sent, got.body)
        assertEquals("Bearer $BOX_KEY", got.headers["authorization"])
        assertEquals("blue", got.headers["x-box-team"])
        assertFalse(got.headers.values.any { key in it }, "the person's key stays on the server's machine")
    }

    @Test
    fun `of a client's headers the provider's server gets the ones forward_headers names, and the key in none`() {
        // Continue's OpenAI provider sends the person's key as `api-key` too; the rest is what any client carries.
        val own = mapOf("api-key" to key, "x-team-tag" to "research", "Cookie" to "session=abc", "x-seat" to "7")
        for ((path, body) in listOf("chat/completions" to CHAT, "models" to null)) {
            val before = asked.size
            val answer = http.send(request(path, body, own = own), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, answer.statusCode(), answer.body())
            val got = asked[before].headers
            assertEquals("Bearer $BOX_KEY", got["authorization"], path)
            assertEquals("research", got["x-team-tag"], path)
            assertEquals("blue", got["x-box-team"], path)
            assertEquals(emptySet<String>(), got.keys intersect setOf("api-key", "cookie", "x-seat"), path)
            assertFalse(got.values.any { key in it }, "the person's key stays on the server's machine ($path)")
        }
    }

    @Test
    fun `the models list is the provider server's own, for a key holder and no one else`() {
        val listed = http.send(request("models", body = null), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, listed.statusCode(), listed.body())
        assertEquals(MODELS, listed.body())

        val before = asked.size
        val refused = http.send(request("models", body = null, bearer = null), HttpResponse.BodyHandlers.ofString())
        assertEquals(401, refused.statusCode())
        assertEquals(setOf("error"), Json.parseToJsonElement(refused.body()).jsonObject.keys, refused.body())
        assertEquals(before, asked.size, "a caller without a key reaches no server")
    }
}

private const val BOX_KEY = "box-key-never-a-persons"
private const val MODELS = """{"object":"list","data":[{"id":"team-model","object":"model","owned_by":"box"}]}"""
private const val COMPLETION =
    """{"choices":[{"index":0,"message":{"role":"assistant","content":"from the box"}}],"usage":{"prompt_tokens":7}}"""
private const val CHAT = """{"model":"team-model","messages":[{"role":"user","content":"hi"}]}"""
private const val WORD = """data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}"""
private const val USAGE = """data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":1}}"""
private const val DONE = "data: [DONE]"
