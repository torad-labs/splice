// An mcp mode reaching `claude` on the door, end to end on a daemon: a launch after the mode is on, whether splice.toml
// turned it on before boot or `PUT /api/modes` while the daemon ran, is handed a `--mcp-config` file naming the mode's
// server at the daemon's own address. Before, only a head's own launch was handed one, so `claude` on the door, the
// launch every seat and most sessions get, never had the mode's tools. The file is one word in front of the person's
// words, which keep the meaning they had, and a relaunch carries none of its own: the launch it makes names it again
// while the mode is on.
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
            assertEquals(emptyList<String>(), ownServers(launch()), "not on for the door by default")
            switch(on = true)
            assertEquals(listOf("visual"), ownServers(launch()))
        }
    }

    @Test
    fun `with no mode on, a launch hands the person's words on exactly as given`(@TempDir tmp: Path) {
        daemon(tmp, "") {
            val words = listOf("-n", "probe", "--mcp-config", "/home/p/mine.json", "--", "--draw", "a chart")
            val argv = launch(words)
            assertEquals(listOf("--settings"), argv.subList(1, 2), "splice's overlay first: $argv")
            assertEquals(words, argv.drop(3))
        }
    }

    @Test
    fun `a launch's own words follow the mode's file exactly as given, a -- among them included`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val asked = listOf(
                listOf("-n", "probe", "--", "--draw", "a chart"),
                listOf("--system-prompt", "--", "--", "go"),
            )
            for (words in asked) {
                val argv = launch(words)
                assertEquals(listOf("visual"), ownServers(argv))
                assertEquals(words, personsWords(argv), "after splice's words: $argv")
            }
        }
    }

    @Test
    fun `a launch whose last option lacks its value keeps it last, for Claude Code to refuse`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            for (last in listOf("--system-prompt", "--model", "-n")) {
                val argv = launch(listOf("--verbose", last))
                assertEquals(listOf("visual"), ownServers(argv))
                assertEquals(last, argv.last(), "nothing fills its value: $argv")
            }
        }
    }

    @Test
    fun `a relaunch names the mode's file once while it is on, and none once it is turned off`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val first = launch(listOf("-n", "probe"))
            val again = relaunch(first)
            assertEquals(listOf("visual"), ownServers(again))
            assertEquals(1, again.count { it.startsWith("--mcp-config") }, "the file is there once: $again")
            assertEquals(listOf("-n", "probe"), LaunchRecipeArgs.callerArgs(again.drop(1), state, wrapped = true))
            switch(on = false)
            val off = relaunch(again)
            assertEquals(emptyList<String>(), ownServers(off))
            assertEquals(0, off.count { it.startsWith("--mcp-config") }, "no server that no longer answers: $off")
        }
    }

    // The build of Nov 25 put the mode's file, and the peer server, after the person's words as a flag and its value;
    // the sessions it started are relaunched by this one.
    @Test
    fun `a relaunch of a session the build of Nov 25 started names the mode's file once, and none once off`(
        @TempDir tmp: Path,
    ) {
        daemon(tmp, "[modes.visual]\non = true") {
            val first = launch(listOf("-n", "probe"))
            val file = first.single { it.startsWith("--mcp-config=") }.substringAfter('=')
            val peer = """{"mcpServers":{"splice-peers":{"type":"stdio","command":"/x/splice"}}}"""
            val earlier = first.filterNot { it.startsWith("--mcp-config=") } +
                listOf("--mcp-config", file, "--mcp-config", peer)
            val again = relaunch(earlier)
            assertEquals(listOf("visual"), ownServers(again))
            assertEquals(listOf("--mcp-config=$file"), again.filter { it.startsWith("--mcp-config") }, "once: $again")
            assertEquals(listOf("-n", "probe"), personsWords(again))
            switch(on = false)
            val off = relaunch(earlier)
            assertEquals(emptyList<String>(), off.filter { it.startsWith("--mcp-config") }, "none: $off")
            assertEquals(listOf("-n", "probe"), off.drop(3), "the person's words alone after the overlay: $off")
        }
    }

    @Test
    fun `claude on the door launched with --strict-mcp-config is handed no mode's server`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val own = """{"mcpServers":{}}"""
            val argv = launch(listOf("--print", "--strict-mcp-config", "--mcp-config", own, "--allowedTools", "Read"))
            assertEquals(emptyList<String>(), ownServers(argv))
            assertEquals(1, argv.count { it.startsWith("--mcp-config") }, "its own config alone: $argv")
        }
    }

    @Test
    fun `a relaunch keeps every file of the person's own, under its flag, beside the mode's once`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") {
            val mine = Files.writeString(tmp.resolve("mine.json"), """{"mcpServers":{}}""").toString()
            val theirs = Files.writeString(tmp.resolve("theirs.json"), """{"mcpServers":{}}""").toString()
            val words = listOf("-n", "probe", "--mcp-config", mine, theirs)
            val again = relaunch(launch(words))
            assertEquals(listOf("visual"), ownServers(again))
            assertEquals(words, personsWords(again), "the person's files keep their flag: $again")
            assertEquals(1, again.count { it.startsWith("--mcp-config=") }, "splice's once: $again")
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

    /** What follows splice's words in [argv]: its binary, the overlay and the file of the daemon's own servers. */
    private fun personsWords(argv: List<String>): List<String> =
        argv.drop(argv.indexOfFirst { it.startsWith("--mcp-config=") } + 1)

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

    /** The servers the file of the daemon's own servers in [argv] names at the daemon's MCP address, by name. */
    private fun ownServers(argv: List<String>): List<String> {
        val files = argv.filter { it.startsWith("--mcp-config=") }.map { it.substringAfter('=') }
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
