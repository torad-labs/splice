package splice.app.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.app.Daemon
import splice.app.cli.server.ServerCommand
import splice.app.desktop.Format
import splice.app.desktop.FormatProvider
import splice.core.client.SPLICE_CLIENT_HEADER
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
import java.time.Instant
class ServerUpstreamAvailabilityTest {
    @TempDir
    private lateinit var dir: Path

    @ParameterizedTest(name = "pooled={0}")
    @ValueSource(booleans = [true, false])
    fun `the server state refuses every turn route until the signal is up again`(pooled: Boolean) = runBlocking {
        val box = FormatProvider(Format.CHAT, "team-model", "box answer")
        val server = AvailabilityServer(dir, box.baseUrl, pooled)
        try {
            server.start()
            val starting = server.awaitState("starting")
            assertEquals(JsonPrimitive("Booting the box."), starting.state()["message"])
            assertEquals(JsonNull, starting.state()["until"])
            assertEquals(JsonNull, starting.state()["last_error"])
            assertEquals(if (pooled) listOf("gpu") else emptyList(), starting.upstreamNames())
            server.assertRefused("Booting the box.")
            server.assertRefused("Booting the box.", clientVersion = "0.1.0")

            val message = "Custom maintenance notice."
            val until = "2020-01-01T00:00:00Z"
            server.write("""{"state":"off","until":"$until","message":"$message"}""")
            val off = server.awaitState("off")
            assertEquals(JsonPrimitive(until), off.state()["until"])
            assertEquals(JsonPrimitive(message), off.state()["message"])
            assertTrue(Instant.parse(off.state().getValue("last_checked").jsonPrimitive.content) > Instant.EPOCH)
            server.assertRefused(message)

            server.write("unknown")
            val failed = server.awaitState("off", error = "unrecognized state output")
            assertEquals(off.state()["message"], failed.state()["message"])
            server.assertRefused(message)

            server.write("up")
            server.awaitState("up")
            server.assertServed()
            server.assertUpgradeRequired()
        } finally {
            server.close()
            box.stop()
        }
    }

    private fun JsonObject.state(): JsonObject = getValue("state").jsonObject

    private fun JsonObject.upstreamNames(): List<String> =
        getValue("upstreams").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
}

private class AvailabilityServer(private val dir: Path, base: String, pooled: Boolean) {
    private val control = TestPorts.reserve()
    private val listen = TestPorts.reserve()
    private val signal = dir.resolve("box.state")
    private val stateDir = dir.resolve("state")
    private val paths = StatePaths(baseOverride = stateDir)
    private val http = HttpClient.newHttpClient()
    private val config = dir.resolve("splice.toml")
    private val routes = listOf(
        TurnRoute("/claude-state/v1/messages", "team-model"),
        TurnRoute("/door/v1/messages", "claude-state--team-model"),
        TurnRoute("/claude-state/v1/chat/completions", "team-model", openAi = true),
    )
    private val daemon: Daemon
    private lateinit var key: String

    init {
        val source = if (pooled) {
            "[[providers.box.upstreams]]\nname = \"gpu\"\nurl = \"$base\""
        } else {
            "base_url = \"$base\""
        }
        val providerKey = Files.writeString(dir.resolve("box.key"), "box-key")
        val toml = """
            [daemon]
            control_port = $control
            [server]
            listen = "127.0.0.1:$listen"
            commands = ["claude-state"]
            [server.client]
            minimum = "0.10.0"
            outdated_status = 426
            [server.state]
            file = "box.state"
            every_seconds = 1
            message_starting = "Booting the box."
            retry_after_starting = 17

            [providers.box]
            dialect = "openai-chat"
            local = false
            auth = { kind = "api-key", file = "$providerKey" }
            ${source.replace("\n", "\n            ")}
            [[providers.box.models]]
            id = "team-model"
            context_window = 131072
            [heads.box]
            provider = "box"
            port = ${TestPorts.reserve()}
            discovery_prefix = "claude-state--"
            pinned_model = "team-model"
            [heads.box.claude]
            command = "claude-state"
        """.trimIndent()
        Files.writeString(config, toml)
        daemon = Daemon(TopologyLoader.parse(toml), statePaths = paths, log = {}, topologyPath = config)
    }

    suspend fun start() {
        write("starting")
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

    fun write(text: String) {
        Files.writeString(signal, text)
    }

    suspend fun close() {
        daemon.stop()
        http.close()
    }

    suspend fun awaitState(expected: String, error: String? = null): JsonObject = withTimeout(10_000) {
        var body = upstreams()
        while (!matches(body, expected, error)) {
            delay(API_CHECK_INTERVAL_MS)
            body = upstreams()
        }
        body
    }

    private fun matches(body: JsonObject, expected: String, error: String?): Boolean {
        val state = body.getValue("state").jsonObject
        val lastError = error?.let(::JsonPrimitive) ?: JsonNull
        return state["state"] == JsonPrimitive(expected) && state["last_error"] == lastError
    }

    fun assertRefused(message: String, clientVersion: String? = null) {
        for (route in routes) {
            val reply = turn(route, clientVersion)
            assertEquals(503, reply.statusCode(), "${route.path}: ${reply.body()}")
            assertEquals("17", reply.headers().firstValue("Retry-After").orElse(null), route.path)
            val body = Json.parseToJsonElement(reply.body()).jsonObject
            val error = body.getValue("error").jsonObject
            assertEquals(JsonPrimitive("splice server: $message"), error["message"])
            if (route.openAi) {
                assertNull(body["type"])
                assertEquals(JsonPrimitive("server_error"), error["type"])
                assertEquals(JsonNull, error["code"])
            } else {
                assertEquals(JsonPrimitive("error"), body["type"])
                assertEquals(JsonPrimitive("api_error"), error["type"])
            }
        }
    }

    fun assertServed() {
        for (route in routes) {
            val reply = turn(route)
            assertEquals(200, reply.statusCode(), "${route.path}: ${reply.body()}")
            assertTrue("box answer" in reply.body(), "${route.path}: ${reply.body()}")
        }
    }

    fun assertUpgradeRequired() {
        for (route in routes) {
            val reply = turn(route, clientVersion = "0.1.0")
            assertEquals(426, reply.statusCode(), "${route.path}: ${reply.body()}")
        }
    }

    private fun upstreams(): JsonObject {
        val ask = request(control, "/api/server/upstreams", MgmtKey(paths).get()).GET().build()
        val reply = http.send(ask, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, reply.statusCode(), reply.body())
        return Json.parseToJsonElement(reply.body()).jsonObject
    }

    private fun turn(route: TurnRoute, clientVersion: String? = null): HttpResponse<String> {
        val body = """{"model":"${route.model}","max_tokens":32,"messages":[{"role":"user","content":"hi"}]}"""
        val ask = request(listen, route.path, key)
            .apply { clientVersion?.let { header(SPLICE_CLIENT_HEADER, it) } }
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return http.send(ask, HttpResponse.BodyHandlers.ofString())
    }

    private fun request(port: Int, path: String, bearer: String): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", "Bearer $bearer")

    private data class TurnRoute(val path: String, val model: String, val openAi: Boolean = false)
}

private const val API_CHECK_INTERVAL_MS = 50L
