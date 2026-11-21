// The model catalog end to end, on fixture providers: a daemon start publishes one catalog of what the signed-in
// providers publish, writes it to model-catalog.json, and every list the door gives (Claude Desktop's gateway
// discovery at /door/v1/models and at /desktop/v1/models, `splice desktop`, `splice claude`'s picker, /api/models)
// names the same models with the provider's own facts. An edit to a provider shows at the next start.
package splice.app.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.core.auth.RefreshAttempt
import splice.core.config.FileHeldKey
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.testing.TestPorts
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/** An Anthropic Messages provider whose model list is [listing], as its `/v1/models` publishes it. */
private class ListingProvider(@Volatile var listing: String) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/") { ex ->
            val _ = ex.requestBody.readBytes()
            val body = if (ex.requestURI.path.endsWith("/v1/models")) listing else "{}"
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }
}

class ModelCatalogDaemonTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val logged = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val controlPort = TestPorts.reserve()
    private val claudePort = TestPorts.reserve()
    private val grokPort = TestPorts.reserve()

    private val claude = ListingProvider(
        """{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5","capabilities":{"effort":""" +
            """{"low":{"supported":true},"high":{"supported":true},"max":{"supported":true}}}}]}""",
    )
    private val xai = ListingProvider(
        """{"data":[{"id":"grok-4.7","display_name":"Grok 4.7","description":"xAI's flagship",""" +
            """"capabilities":{"reasoning_effort":["low","high"]}},{"id":"grok-mini","display_name":"Grok Mini"}]}""",
    )

    @TempDir
    lateinit var tmp: Path

    private val statePaths by lazy { StatePaths(baseOverride = tmp.resolve("state")) }
    private val mgmt by lazy { MgmtKey(statePaths).get() }
    private val terminal by lazy { FileHeldKey(statePaths.terminalKeyFile, "splice-terminal-", "claude", "", {}).get() }

    @Test
    fun `a start publishes one catalog every list agrees on, and a provider edit shows at the next start`() =
        runBlocking {
            val claudeKey = tmp.resolve("claude.json").also { Files.writeString(it, """{"api_key":"sk-claude-x"}""") }
            val xaiKey = tmp.resolve("xai.json").also { Files.writeString(it, """{"api_key":"xai-fixture-secret"}""") }
            try {
                running(toml(claudeKey, xaiKey, sonnet = false)) { firstStart() }
                // The edits: splice.toml declares a second Claude model, and xAI's list renames its flagship.
                xai.listing = xai.listing.replace("Grok 4.7", "Grok 4.7 Fast")
                running(toml(claudeKey, xaiKey, sonnet = true)) { afterEdits() }
            } finally {
                claude.stop()
                xai.stop()
                client.close()
            }
        }

    private suspend fun firstStart() {
        val discovery = get(terminal, "/door/v1/models")
        assertEquals(discovery, get(terminal, "/desktop/v1/models"))
        val rows = discovery.getValue("data").jsonArray.map { it.jsonObject }
        assertEquals(listOf("claude-opus-5-5", "claude-xai--4.7", "claude-xai--mini"), rows.map { it.str("id") })

        val opus = rows.first { it.str("id") == "claude-opus-5-5" }
        assertEquals("Claude Opus 5.5", opus.str("display_name"))
        assertEquals("opus", opus.str("anthropic_family_tier"))
        assertEquals("max", opus.str("max_effort_level"))

        // Another lab's model: its provider's name, description and levels, under the name Claude's clients keep; it
        // takes the newest Opus's Effort control, ended at the highest level its provider lists.
        val grok = rows.first { it.str("id") == "claude-xai--4.7" }
        assertEquals("Grok 4.7", grok.str("display_name"))
        assertEquals("xAI's flagship", grok.str("description"))
        assertEquals("high", grok.str("max_effort_level"))
        assertEquals("claude-opus-5-5", grok.str("behaves_as"))
        assertNull(grok["anthropic_family_tier"])

        // A model whose provider says nothing of its reasoning gets no effort and no Claude model to behave as.
        val mini = rows.first { it.str("id") == "claude-xai--mini" }
        assertNull(mini["max_effort_level"])
        assertNull(mini["behaves_as"])
        assertNull(mini["description"])

        // The file and /api/models are the same snapshot, and the file holds no credential.
        val file = Files.readString(statePaths.modelCatalogFile)
        assertFalse("sk-claude-x" in file || "xai-fixture-secret" in file)
        val kept = json.parseToJsonElement(file).jsonObject.getValue("models")
        assertEquals(kept, get(mgmt, "/api/models").getValue("models"))
        assertEquals(listOf("claude-opus-5-5", "grok-4.7", "grok-mini"), kept.jsonArray.map { it.jsonObject.str("id") })

        // `splice desktop` names Desktop's list; `splice claude`'s picker the same models by their selectors.
        val desktop = get(mgmt, "/api/desktop").getValue("models").jsonArray.map { it.jsonObject }
        assertEquals(rows.map { it.str("id") }, desktop.map { it.str("model") })
        val picker = get(mgmt, "/api/claude").getValue("models").jsonArray.map { it.jsonObject }
        assertEquals(rows.map { it.str("display_name") }, picker.map { it.str("label") })
    }

    /** A start asks every provider before it publishes, so its first answer already holds both edits. */
    private suspend fun afterEdits() {
        val rows = get(terminal, "/door/v1/models").getValue("data").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-xai--4.7", "claude-xai--mini"),
            rows.map { it.str("id") },
        )
        assertEquals("Grok 4.7 Fast", rows.first { it.str("id") == "claude-xai--4.7" }.str("display_name"))
        val file = json.parseToJsonElement(Files.readString(statePaths.modelCatalogFile)).jsonObject
        assertEquals(file.getValue("models"), get(mgmt, "/api/models").getValue("models"))
        val picker = get(mgmt, "/api/claude").getValue("models").jsonArray.map { it.jsonObject.str("label") }
        assertEquals(listOf("Claude Opus 5.5", "claude-sonnet-5-5", "Grok 4.7 Fast", "Grok Mini"), picker)
    }

    /** A daemon started on [toml] over this test's state, [checks] run against it, and stopped. */
    private suspend fun running(toml: String, checks: suspend () -> Unit) {
        val daemon = boot(statePaths, toml)
        try {
            checks()
        } finally {
            daemon.stop()
        }
    }

    private suspend fun boot(statePaths: StatePaths, toml: String): Daemon {
        val daemon = Daemon(
            topology = TopologyLoader.parse(toml),
            statePaths = statePaths,
            log = { logged.add(it) },
            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
        )
        daemon.start()
        runCatching { awaitListening(controlPort, claudePort, grokPort) }
            .getOrElse { throw AssertionError("${it.message}; daemon log:\n${logged.joinToString("")}", it) }
        return daemon
    }

    /** [path] on the control port, asked with [key]: the terminal's for the door, the management key for /api. */
    private suspend fun get(key: String, path: String): JsonObject {
        val response = client.get("http://127.0.0.1:$controlPort$path") { header("Authorization", "Bearer $key") }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun toml(claudeKey: Path, xaiKey: Path, sonnet: Boolean): String = """
        [daemon]
        control_port = $controlPort

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "${claude.baseUrl}"
        auth = { kind = "api-key", file = "${claudeKey.esc()}" }
        [[providers.anthropic.models]]
        id = "claude-opus-5-5"
        context_window = 200000
    """.trimIndent() + (if (sonnet) "\n" + sonnetRow() else "") + "\n" + """
        [providers.xai]
        dialect = "anthropic-passthrough"
        base_url = "${xai.baseUrl}"
        auth = { kind = "api-key", file = "${xaiKey.esc()}" }

        [heads.anthropic]
        provider = "anthropic"
        port = $claudePort
        discovery_prefix = "claude-anthropic--"
        pinned_model = "claude-opus-5-5"
        claude = { command = "claude-anthropic" }

        [heads.grok]
        provider = "xai"
        port = $grokPort
        discovery_prefix = "claude-grok--"
        pinned_model = "grok-4.7"
        claude = { command = "claude-grok" }
    """.trimIndent()

    private fun sonnetRow(): String = """
        [[providers.anthropic.models]]
        id = "claude-sonnet-5-5"
        context_window = 200000
    """.trimIndent()
}

private fun Path.esc() = toString().replace("\\", "/")
