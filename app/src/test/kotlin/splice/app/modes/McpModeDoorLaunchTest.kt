// An mcp mode reaching `claude` on the door, end to end on a daemon: a launch after the mode is on, whether splice.toml
// turned it on before boot or `PUT /api/modes` while the daemon ran, is handed a `--mcp-config` file naming the mode's
// server at the daemon's own address. Before, only a head's own launch was handed one, so `claude` on the door, the
// launch every seat and most sessions get, never had the mode's tools.
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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

    @Test
    fun `claude on the door is handed the server of an mcp mode splice toml turned on`(@TempDir tmp: Path) {
        daemon(tmp, "[modes.visual]\non = true") { mgmt, home ->
            assertEquals(listOf("visual"), ownServers(launch(mgmt, home)))
        }
    }

    @Test
    fun `claude on the door is handed the server of an mcp mode switched on while the daemon runs`(@TempDir tmp: Path) {
        daemon(tmp, "") { mgmt, home ->
            assertEquals(emptyList<String>(), ownServers(launch(mgmt, home)), "the mode ships off")
            val switched = client.put("http://127.0.0.1:$controlPort/api/modes/visual") {
                header("Authorization", "Bearer $mgmt")
                header("Content-Type", "application/json")
                setBody("""{"on":true}""")
            }
            assertEquals(200, switched.status.value, switched.bodyAsText())
            assertEquals(listOf("visual"), ownServers(launch(mgmt, home)))
        }
    }

    /** A daemon over a splice.toml with one provider, no heads and [modes], carrying the modes its jar carries. */
    private fun daemon(tmp: Path, modes: String, check: suspend (String, Path) -> Unit) {
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
                        check(mgmt, home)
                    } finally {
                        daemon.stop()
                    }
                }
            }
        }
        provider.stop()
        client.close()
    }

    /** The recipe a launch of `claude` answers, asked as the shim asks. */
    private suspend fun launch(mgmt: String, home: Path): JsonObject {
        val response = client.post("http://127.0.0.1:$controlPort/launch/claude") {
            header("Authorization", "Bearer $mgmt")
            header("Content-Type", "application/json")
            setBody("""{"cwd":${JsonPrimitive(home.toString())}}""")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject
    }

    /** The servers the launch's `--mcp-config` files name at the daemon's own MCP address, by name. */
    private fun ownServers(recipe: JsonObject): List<String> {
        val argv = recipe.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
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
