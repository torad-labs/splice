// An mcp mode reaching `claude` on the door, end to end on a daemon: a launch after the mode is on, whether splice.toml
// turned it on before boot or `PUT /api/modes` while the daemon ran, is handed a `--mcp-config` file naming the mode's
// server at the daemon's own address. Before, only a head's own launch was handed one, so `claude` on the door, the
// launch every seat and most sessions get, never had the mode's tools. The file is a flag before a `--` in the
// person's words, and a relaunch carries none of its own: the launch it makes names it again while the mode is on.
// A host that reads a page with the management key alone, naming no caller, is answered by the mode at its address.
package splice.app.modes

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import splice.app.desktop.setup.DesktopManagedPolicy
import splice.core.auth.RefreshAttempt
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.launch.recipe.LaunchRecipeArgs
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/** An Anthropic Messages provider whose `/v1/models` lists one model, so the door has something to serve. */
private class DoorModeProvider {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val listing = """{"data":[{"id":"grok-4.7"}]}""".toByteArray()
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

class McpModeDoorLaunchTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()

    /** The running daemon a test asks: its management key, the person's home and splice's state folder. */
    private inner class Running(private val mgmt: String, private val home: Path, val state: Path) {
        /** The argv a launch of `claude` answers for the person's [words], asked as the shim asks. */
        suspend fun launch(words: List<String> = emptyList()): List<String> {
            val args = JsonArray(words.map(::JsonPrimitive))
            val response = client.post("http://127.0.0.1:$controlPort/launch/claude") {
                header("Authorization", "Bearer $mgmt")
                header("Content-Type", "application/json")
                setBody("""{"cwd":${JsonPrimitive(home.toString())},"args":$args}""")
            }
            val body = response.bodyAsText()
            assertEquals(200, response.status.value, body)
            return json.parseToJsonElement(body).jsonObject.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
        }

        /** The visual mode switched [on] for every head, as the console's switch does. */
        suspend fun switch(on: Boolean) {
            val switched = client.put("http://127.0.0.1:$controlPort/api/modes/visual") {
                header("Authorization", "Bearer $mgmt")
                header("Content-Type", "application/json")
                setBody("""{"on":$on}""")
            }
            assertEquals(200, switched.status.value, switched.bodyAsText())
        }

        /** What a Continue or a model move starts the session of [argv] with again: the person's words, which the
         *  wrapped `claude` launches on the door once more. */
        suspend fun relaunch(argv: List<String>): List<String> =
            launch(LaunchRecipeArgs.callerArgs(argv.drop(1), state, wrapped = true))

        /** The result of [body] posted to the visual mode's address with the management key alone, naming no caller
         *  and no session, as the Eli app reads a page (splice-mcp-apps.ts). */
        suspend fun bare(body: String): JsonObject {
            val response = client.post("http://127.0.0.1:$controlPort/mcp/visual") {
                header("Authorization", "Bearer $mgmt")
                header("Accept", "application/json, text/event-stream")
                header("Content-Type", "application/json")
                setBody(body)
            }
            val text = response.bodyAsText()
            assertEquals(200, response.status.value, text)
            return json.parseToJsonElement(text).jsonObject.getValue("result").jsonObject
        }
    }

    @Test
    fun `claude on the door is handed the server of an mcp mode splice toml turned on`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            assertEquals(listOf("visual"), ownServers(launch()))
        }
    }

    @Test
    fun `claude on the door is handed the server of an mcp mode switched on while the daemon runs`(@TempDir tmp: Path) {
        daemon(tmp, "") {
            assertEquals(emptyList<String>(), ownServers(launch()), "the mode ships off")
            switch(on = true)
            assertEquals(listOf("visual"), ownServers(launch()))
        }
    }

    @Test
    fun `a launch whose own words hold -- is handed the file as a flag before them`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val argv = launch(listOf("-n", "probe", "--", "--draw", "a chart"))
            assertEquals(listOf("visual"), ownServers(argv))
            assertEquals(listOf("--", "--draw", "a chart"), argv.takeLast(3), "the prompt's words are as given")
            val file = argv.indexOf("--mcp-config")
            assertEquals(true, file in 0..<argv.indexOf("--"), "the file is before the --: $argv")
        }
    }

    @Test
    fun `a relaunch names the mode's file once while it is on, and none once it is turned off`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val first = launch(listOf("-n", "probe"))
            val again = relaunch(first)
            assertEquals(listOf("visual"), ownServers(again))
            assertEquals(1, again.count { it == "--mcp-config" }, "the file is there once: $again")
            assertEquals(listOf("-n", "probe"), LaunchRecipeArgs.callerArgs(again.drop(1), state, wrapped = true))
            switch(on = false)
            val off = relaunch(again)
            assertEquals(emptyList<String>(), ownServers(off))
            assertEquals(0, off.count { it == "--mcp-config" }, "no server that no longer answers: $off")
        }
    }

    @Test
    fun `a launch whose system prompt is -- is handed the file before the -- that ends options`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val argv = launch(listOf("--system-prompt", "--", "--", "go"))
            assertEquals(listOf("visual"), ownServers(argv))
            val file = argv.indexOf("--mcp-config")
            assertEquals(listOf("--system-prompt", "--"), argv.subList(file - 2, file), "the system prompt is --")
            assertEquals(listOf("--", "go"), argv.subList(file + 2, argv.size), "the prompt is go: $argv")
        }
    }

    @Test
    fun `claude on the door launched with --strict-mcp-config is handed no mode's server`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val own = """{"mcpServers":{}}"""
            val argv = launch(listOf("--print", "--strict-mcp-config", "--mcp-config", own, "--allowedTools", "Read"))
            assertEquals(emptyList<String>(), ownServers(argv))
            assertEquals(1, argv.count { it == "--mcp-config" }, "its own config alone: $argv")
        }
    }

    @Test
    fun `a relaunch keeps the person's file that followed splice's under one flag`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val mine = Files.writeString(tmp.resolve("mine.json"), """{"mcpServers":{}}""")
            val first = launch(listOf("-n", "probe"))
            val own = first[first.indexOf("--mcp-config") + 1]
            val started = first.takeWhile { it != "--mcp-config" } + listOf("--mcp-config", own, mine.toString())
            val again = relaunch(started)
            assertEquals(listOf("visual"), ownServers(again))
            val theirs = again.indexOf(mine.toString())
            assertEquals("--mcp-config", again[theirs - 1], "the person's file keeps a flag: $again")
            assertEquals(2, again.count { it == "--mcp-config" }, "the person's and splice's, once each: $again")
        }
    }

    @Test
    fun `a bare bearer lists an on mode's tools and reads its page at its address, as a host reading a view does`(
        @TempDir tmp: Path,
    ) {
        daemon(tmp, "[modes.visual]\non = true") {
            val listed = bare("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").getValue("tools").jsonArray
            assertEquals(
                listOf("read_me", "show_widget", "html_render"),
                listed.map { it.jsonObject.getValue("name").jsonPrimitive.content },
            )
            val read = bare(
                """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"ui://visual/reply.html"}}""",
            )
            val page = read.getValue("contents").jsonArray.single().jsonObject
            assertEquals("text/html;profile=mcp-app", page.getValue("mimeType").jsonPrimitive.content)
            assertTrue(page.getValue("text").jsonPrimitive.content.contains("<"), "the page's html")
        }
    }

    /** A daemon over a splice.toml with one provider, no heads and [modes], carrying the modes its jar carries. */
    private fun daemon(tmp: Path, modes: String, check: suspend Running.() -> Unit) {
        val provider = DoorModeProvider()
        val home = Files.createDirectories(tmp.resolve("home"))
        val fixture = mapOf("XDG_CONFIG_HOME" to tmp.resolve("config").toString())
        val config = tmp.resolve("splice.toml").also { Files.writeString(it, toml(tmp, provider.baseUrl, modes)) }
        UserHome.within(home) {
            DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json")) {
                runBlocking {
                    val statePaths =
                        StatePaths(baseOverride = tmp.resolve("state"), envReader = EnvReader { fixture[it] })
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
                        Running(mgmt, home, statePaths.stateDir).check()
                    } finally {
                        daemon.stop()
                    }
                }
            }
        }
        provider.stop()
        client.close()
    }

    /** The servers the launch's `--mcp-config` files name at the daemon's own MCP address, by name. */
    private fun ownServers(argv: List<String>): List<String> {
        val files = argv.indices.filter { argv[it] == "--mcp-config" }.map { argv[it + 1] }
            .filter { !it.trimStart().startsWith("{") }
        return files.flatMap { file ->
            val servers = json.parseToJsonElement(Files.readString(Path.of(file))).jsonObject
                .getValue("mcpServers").jsonObject
            servers.filter { (name, entry) ->
                entry.jsonObject.getValue("url").jsonPrimitive.content == "http://127.0.0.1:$controlPort/mcp/$name"
            }.keys
        }
    }

    private fun toml(tmp: Path, base: String, modes: String): String {
        val key = tmp.resolve("xai.json").also { Files.writeString(it, """{"api_key":"xai-fixture"}""") }
        return """
            |[daemon]
            |control_port = $controlPort
            |
            |[providers.xai]
            |dialect = "anthropic-passthrough"
            |base_url = "$base"
            |auth = { kind = "api-key", file = "${key.toString().replace("\\", "/")}" }
            |pinned_model = "grok-4.7"
            |
            |$modes
        """.trimMargin()
    }
}
