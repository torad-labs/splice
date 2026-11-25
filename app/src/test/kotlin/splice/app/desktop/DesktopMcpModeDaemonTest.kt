// An mcp mode reaching Claude Desktop's own sessions, end to end on a daemon over a fixture home: with Desktop on the
// gateway and the mode on, Desktop's configuration names the mode's server through splice's stdio bridge, and a session
// that starts the bridge lists the mode's tools with their `_meta` as the mode's folder writes it, calls one and reads
// its page. Switched on and off through `PUT /api/modes` while the daemon runs, the configuration follows without a
// restart, for Desktop to read at its next start.
package splice.app.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
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
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.app.cli.mcp.McpStdioCommand
import splice.app.cli.mcp.McpStdioPipes
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.app.mcp.McpFrontCheck
import splice.app.mcp.McpHarnessSyncSeams
import splice.app.modes.ModeBoot
import splice.client.desktop.DesktopAppConfig
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.terminal.TerminalOutput
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/** An Anthropic Messages provider whose `/v1/models` lists one model, so the door has something to serve. */
private class ModeDesktopProvider {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val listing = """{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5"}]}""".toByteArray()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/") { ex ->
            val _ = ex.requestBody.readBytes()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, listing.size.toLong())
            ex.responseBody.use { it.write(listing) }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }
}

/** The fixture's folders and the daemon's key, as a check reads them. */
private data class ModeDesktopFixture(val tmp: Path, val env: EnvReader, val statePaths: StatePaths, val mgmt: String) {
    val home: Path get() = tmp.resolve("home")

    /** Desktop's gateway profile, where its `claude_desktop_config.json` is. */
    val profile: Path get() = checkNotNull(DesktopAppConfig(env).library.parent)
}

class DesktopMcpModeDaemonTest {
    private val client = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 30_000 } }
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()

    @Test
    fun `Desktop on the gateway lists a mode that is on, and its session lists, calls and reads it through the bridge`(
        @TempDir tmp: Path,
    ) {
        daemon(tmp, "[modes.visual]\non = true") { fixture ->
            post(fixture.mgmt, "/api/desktop/on")
            val bridge = fixture.statePaths.stateDir.resolve("desktop-mcp").toAbsolutePath().toString()
            val entry = servers(fixture).getValue("visual").jsonObject
            assertEquals(bridge, entry.getValue("command").jsonPrimitive.content)
            assertEquals(listOf("visual"), entry.getValue("args").jsonArray.map { it.jsonPrimitive.content })
            val written = Files.readString(fixture.profile.resolve(DESKTOP_CONFIG))
            assertFalse(
                "Bearer" in written || "headers" in written || "127.0.0.1:$controlPort" in written,
                "Desktop's file names no key, header or address: $written",
            )

            val answers = session(
                fixture,
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                    """"capabilities":{},"clientInfo":{"name":"desktop-fixture","version":"1"}}}""",
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
                """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"read_me","arguments":{}}}""",
                """{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"ui://visual/reply.html"}}""",
            )
            assertEquals("visual", answers.getValue(1).getValue("serverInfo").jsonObject.str("name"))
            val tools = answers.getValue(2).getValue("tools").jsonArray.map { it.jsonObject }
            assertEquals(listOf("read_me", "show_widget", "html_render"), tools.map { it.str("name") })
            val meta = """{"ui":{"resourceUri":"ui://visual/reply.html","visibility":["model"]}}"""
            assertEquals(meta, tools.single { it.str("name") == "show_widget" }.getValue("_meta").toString())
            assertEquals(meta, tools.single { it.str("name") == "html_render" }.getValue("_meta").toString())
            assertEquals("false", answers.getValue(3).getValue("isError").toString())
            val page = answers.getValue(4).getValue("contents").jsonArray.single().jsonObject
            assertEquals("text/html;profile=mcp-app", page.str("mimeType"))
            assertEquals(resource("splice-modes/visual/resources/reply.html"), page.str("text"))
        }
    }

    @Test
    fun `a mode switched on and off while the daemon runs is put in and taken out of Desktop's configuration`(
        @TempDir tmp: Path,
    ) {
        daemon(tmp, "") { fixture ->
            post(fixture.mgmt, "/api/desktop/on")
            assertFalse("visual" in servers(fixture), "the mode ships off")
            switch(fixture.mgmt, """{"on":true}""")
            assertEquals(listOf("visual"), servers(fixture).keys.toList())
            switch(fixture.mgmt, """{"on":false}""")
            assertFalse("visual" in servers(fixture))
            val said = refusal(fixture)
            assertTrue("no MCP server named 'visual' is active" in said, "a Desktop holding the entry is told: $said")
        }
    }

    /** A daemon over a splice.toml with one provider, no heads and [modes], carrying the modes its jar carries, with
     *  Desktop's configuration and the daemon's state under [tmp]. */
    private fun daemon(tmp: Path, modes: String, check: suspend (ModeDesktopFixture) -> Unit) {
        val provider = ModeDesktopProvider()
        val home = Files.createDirectories(tmp.resolve("home"))
        val config = tmp.resolve("splice.toml").also { Files.writeString(it, toml(tmp, provider.baseUrl, modes)) }
        val fixture = mapOf(
            "HOME" to home.toString(),
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
            "SPLICE_STATE_DIR" to tmp.resolve("state").toString(),
            "SPLICE_CONFIG" to config.toString(),
            "SPLICE_CONTROL_PORT" to controlPort.toString(),
        )
        val env = EnvReader { fixture[it] }
        Files.createDirectories(DesktopAppConfig(env).library)
        try {
            UserHome.within(home) {
                DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json")) {
                    runBlocking {
                        val statePaths = StatePaths(baseOverride = tmp.resolve("state"), envReader = env)
                        val mgmt = MgmtKey(statePaths).get()
                        val daemon = Daemon(
                            topology = TopologyLoader.loadOrMaterialize(config),
                            statePaths = statePaths,
                            log = {},
                            refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
                            topologyPath = config,
                        )
                        daemon.modeBoot = ModeBoot(Thread.currentThread().contextClassLoader)
                        daemon.start()
                        try {
                            awaitListening(controlPort)
                            check(ModeDesktopFixture(tmp, env, statePaths, mgmt))
                        } finally {
                            daemon.stop()
                        }
                    }
                }
            }
        } finally {
            provider.stop()
            client.close()
        }
    }

    /** The servers Desktop's gateway profile lists; none when splice has written it none. */
    private fun servers(fixture: ModeDesktopFixture): JsonObject {
        val file = fixture.profile.resolve(DESKTOP_CONFIG)
        if (!Files.exists(file)) return JsonObject(emptyMap())
        val config = json.parseToJsonElement(Files.readString(file)).jsonObject
        return config["mcpServers"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** What a session's [lines] are answered, by request id, through the bridge Desktop starts for `visual`. */
    private suspend fun session(fixture: ModeDesktopFixture, vararg lines: String): Map<Int, JsonObject> {
        val out = ByteArrayOutputStream()
        val err = mutableListOf<String>()
        val input = ByteArrayInputStream(lines.joinToString("\n", postfix = "\n").toByteArray())
        val ran = bridge(fixture, TerminalOutput { err += it }, McpStdioPipes(input, out)).run(listOf("visual"))
        assertEquals(0, ran, err.toString())
        return out.toString(Charsets.UTF_8).lines().filter(String::isNotBlank).map { json.parseToJsonElement(it) }
            .associate { reply ->
                val answer = reply.jsonObject
                answer.getValue("id").jsonPrimitive.content.toInt() to
                    checkNotNull(answer["result"]?.jsonObject) { "an error came back: $answer" }
            }
    }

    /** What the bridge says on stderr when it can't reach `visual`. */
    private suspend fun refusal(fixture: ModeDesktopFixture): String {
        val err = mutableListOf<String>()
        val pipes = McpStdioPipes(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
        assertEquals(1, bridge(fixture, TerminalOutput { err += it }, pipes).run(listOf("visual")))
        return err.joinToString("\n")
    }

    private fun bridge(fixture: ModeDesktopFixture, err: TerminalOutput, pipes: McpStdioPipes) = McpStdioCommand(
        err = err,
        env = fixture.env,
        homeOverride = fixture.home,
        seams = McpHarnessSyncSeams(frontLive = McpFrontCheck { _, _ -> false }, controlPortOverride = controlPort),
        pipes = pipes,
    )

    private suspend fun post(mgmt: String, path: String) {
        val response = client.post("http://127.0.0.1:$controlPort$path") {
            header("Authorization", "Bearer $mgmt")
            setBody("{}")
        }
        assertEquals(200, response.status.value, response.bodyAsText())
    }

    private suspend fun switch(mgmt: String, body: String) {
        val response = client.put("http://127.0.0.1:$controlPort/api/modes/visual") {
            header("Authorization", "Bearer $mgmt")
            header("Content-Type", "application/json")
            setBody(body)
        }
        assertEquals(200, response.status.value, response.bodyAsText())
    }

    private fun resource(name: String): String =
        checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(name)) { name }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private fun toml(tmp: Path, base: String, modes: String): String {
        val key = tmp.resolve("anthropic.json").also { Files.writeString(it, """{"api_key":"sk-fixture"}""") }
        return """
            |[daemon]
            |control_port = $controlPort
            |
            |[providers.anthropic]
            |dialect = "anthropic-passthrough"
            |base_url = "$base"
            |auth = { kind = "api-key", file = "${key.toString().replace("\\", "/")}" }
            |pinned_model = "claude-opus-5-5"
            |[[providers.anthropic.models]]
            |id = "claude-opus-5-5"
            |context_window = 200000
            |
            |$modes
        """.trimMargin()
    }
}

private const val DESKTOP_CONFIG = "claude_desktop_config.json"
