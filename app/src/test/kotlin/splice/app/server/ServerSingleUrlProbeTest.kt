// #791, the readiness probe for a provider on one base_url (docs/specs/server-mode.md, `[server.probe] single_url`):
// a provider that is one server, named in `single_url`, is asked the same minimal completion a pool's members are, and
// takes no turn before one passes. A running daemon serves one such provider on every turn route; the test lets its
// server refuse completions, then answer them, and reads what a key holder's turns get and what reached the server.
package splice.app.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.app.cli.server.ServerCommand
import splice.app.desktop.Format
import splice.app.desktop.FormatProvider
import splice.core.config.MgmtKey
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
import java.time.Duration

class ServerSingleUrlProbeTest {
    @TempDir
    private lateinit var dir: Path

    @Test
    fun `a single-url provider takes no turn until its completion probe passes, and is refused with Retry-After`() =
        runBlocking {
            val box = FormatProvider(Format.CHAT, "team-model", "box answer")
            box.refusing = 503 to """{"error":{"message":"loading model"}}"""
            val server = ProbedServer(dir, box.baseUrl)
            try {
                server.start()
                assertEquals(listOf("box" to false), server.upstreams(), "the one server is listed, held out")
                for (route in ROUTES) {
                    val reply = server.turn(route)
                    assertEquals(503, reply.statusCode(), "${route.path}: ${reply.body()}")
                    assertEquals("1", reply.headers().firstValue("Retry-After").orElse(null), route.path)
                    val error = Json.parseToJsonElement(reply.body()).jsonObject.getValue("error").jsonObject
                    val said = error.getValue("message").jsonPrimitive.content
                    assertTrue("no upstream of box is up (box)" in said, "${route.path}: $said")
                }
                assertTrue(box.turns.none { TURN_TEXT in it.body }, "only probes reached the server, never a turn")

                box.refusing = null
                server.awaitUp()
                for (route in ROUTES) {
                    val reply = server.turn(route)
                    assertEquals(200, reply.statusCode(), "${route.path}: ${reply.body()}")
                    assertTrue("box answer" in reply.body(), "${route.path}: ${reply.body()}")
                }
            } finally {
                server.close()
                box.stop()
            }
        }
}

private class ProbedServer(private val dir: Path, base: String) {
    private val control = TestPorts.reserve()
    private val listen = TestPorts.reserve()
    private val stateDir = dir.resolve("state")
    private val paths = StatePaths(baseOverride = stateDir)
    private val http = HttpClient.newHttpClient()
    private val config = dir.resolve("splice.toml")
    private val daemon: Daemon
    private lateinit var key: String

    init {
        val providerKey = Files.writeString(dir.resolve("box.key"), "box-key")
        val toml = """
            [daemon]
            control_port = $control
            [server]
            listen = "127.0.0.1:$listen"
            commands = ["claude-solo"]
            [server.probe]
            kind = "completion"
            model = "team-model"
            every_seconds = 1
            timeout_seconds = 5
            single_url = ["box"]

            [providers.box]
            dialect = "openai-chat"
            local = false
            auth = { kind = "api-key", file = "$providerKey" }
            base_url = "$base"
            [[providers.box.models]]
            id = "team-model"
            context_window = 131072
            [heads.box]
            provider = "box"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-solo--"
            pinned_model = "team-model"
            [heads.box.claude]
            command = "claude-solo"
        """.trimIndent()
        Files.writeString(config, toml)
        daemon = Daemon(TopologyLoader.parse(toml), statePaths = paths, log = {}, topologyPath = config)
    }

    suspend fun start() {
        daemon.start()
        awaitListening(control, listen)
        val env = mapOf("SPLICE_CONFIG" to "$config", "SPLICE_STATE_DIR" to "$stateDir", "HOME" to "$dir")
        val printed = StringBuilder()
        val command = ServerCommand(
            TerminalOutput { printed.appendLine(it) },
            TerminalOutput { },
            EnvReader { env[it] },
        )
        assertEquals(0, command.run(listOf("key", "create", "--person", "ola@example.com")), printed.toString())
        key = printed.lines().first()
    }

    suspend fun close() {
        daemon.stop()
        http.close()
    }

    /** Each upstream the operator's list shows, with whether it takes turns. */
    fun upstreams(): List<Pair<String, Boolean>> {
        val ask = request(control, "/api/server/upstreams", MgmtKey(paths).get()).GET().build()
        val reply = http.send(ask, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reply.statusCode(), reply.body())
        return Json.parseToJsonElement(reply.body()).jsonObject.getValue("upstreams").jsonArray.map { row ->
            val upstream: JsonObject = row.jsonObject
            upstream.getValue("name").jsonPrimitive.content to (upstream["healthy"] == JsonPrimitive(true))
        }
    }

    suspend fun awaitUp() = withTimeout(10_000) {
        while (upstreams() != listOf("box" to true)) delay(API_CHECK_INTERVAL_MS)
    }

    fun turn(route: TurnRoute): HttpResponse<String> {
        val said = """[{"role":"user","content":"$TURN_TEXT"}]"""
        val body = """{"model":"${route.model}","max_tokens":32,"messages":$said}"""
        val ask = request(listen, route.path, key)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(ask, HttpResponse.BodyHandlers.ofString())
    }

    private fun request(port: Int, path: String, bearer: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $bearer")
}

private data class TurnRoute(val path: String, val model: String)

private val ROUTES = listOf(
    TurnRoute("/claude-solo/v1/messages", "team-model"),
    TurnRoute("/door/v1/messages", "claude-solo--team-model"),
    TurnRoute("/claude-solo/v1/chat/completions", "team-model"),
)

// What every turn says, which a completion probe never does.
private const val TURN_TEXT = "hello from a key holder"
private const val API_CHECK_INTERVAL_MS = 50L
