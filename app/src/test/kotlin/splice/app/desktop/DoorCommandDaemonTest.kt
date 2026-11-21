// A door command (`[door.commands.<name>]`) launches on the door, end to end on a daemon over a fixture home: a session
// of it starts on its head's pinned model, and its picker is that head's rows, or the door's whole picker where it says
// `models = "door"`, from the same catalog. And `splice claude --json` says each head's provider and whether it is
// listed, with the reason where it is not.
package splice.app.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.app.Daemon
import splice.client.desktop.DesktopManagedPolicy
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

/** An Anthropic Messages provider whose `/v1/models` lists [ids]. */
private class ListsProvider(ids: List<String>) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val listing = ids.joinToString(",", """{"data":[""", "]}") { """{"id":"$it"}""" }.toByteArray()
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

class DoorCommandDaemonTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()
    private val claudePort = TestPorts.reserve()
    private val grokPort = TestPorts.reserve()
    private val moonPort = TestPorts.reserve()

    @Test
    fun `a door command starts on its head's model with its rows, or the door's whole picker`(@TempDir tmp: Path) {
        val anthropic = ListsProvider(listOf("claude-opus-5-5"))
        val xai = ListsProvider(listOf("grok-4.7", "grok-mini"))
        val home = Files.createDirectories(tmp.resolve("home"))
        val fixture = mapOf(
            "XDG_CONFIG_HOME" to tmp.resolve("config").toString(),
        )
        fixtureHome(home, tmp) {
            runBlocking {
                val statePaths = StatePaths(baseOverride = tmp.resolve("state"), envReader = EnvReader { fixture[it] })
                val mgmt = MgmtKey(statePaths).get()
                val daemon = Daemon(
                    topology = TopologyLoader.parse(toml(tmp, anthropic.baseUrl, xai.baseUrl)),
                    statePaths = statePaths,
                    log = {},
                    refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
                )
                daemon.start()
                try {
                    awaitListening(controlPort, claudePort, grokPort)
                    val grokOnly = launch(mgmt, "acme")
                    assertEquals("claude-grok--grok-4.7", grokOnly.first)
                    assertEquals(listOf("claude-grok--grok-4.7", "claude-grok--grok-mini"), grokOnly.second)
                    val everything = launch(mgmt, "everything")
                    assertEquals("claude-grok--grok-4.7", everything.first)
                    assertEquals(
                        listOf("claude-anthropic--claude-opus-5-5", "claude-grok--grok-4.7", "claude-grok--grok-mini"),
                        everything.second,
                    )
                    heads(mgmt)
                } finally {
                    daemon.stop()
                }
            }
        }
        anthropic.stop()
        xai.stop()
        client.close()
    }

    /** `splice claude --json`'s heads: each one's provider, and whether it is in the picker and why not. */
    private suspend fun heads(mgmt: String) {
        val response = client.get("http://127.0.0.1:$controlPort/api/claude") {
            header("Authorization", "Bearer $mgmt")
        }
        val heads = json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("heads").jsonArray
            .associate { it.jsonObject.str("head") to it.jsonObject }
        assertEquals("anthropic", heads.getValue("anthropic").str("provider"))
        assertEquals("true", heads.getValue("grok").str("listed"))
        val moon = heads.getValue("moon")
        assertEquals("false", moon.str("listed"))
        assertEquals("moonshot", moon.str("provider"))
        assertEquals(true, moon.str("reason").contains("key"), moon.toString())
    }

    /** The model a launch of door command [name] starts on, and the rows of its picker. */
    private suspend fun launch(mgmt: String, name: String): Pair<String, List<String>> {
        val response = client.post("http://127.0.0.1:$controlPort/launch/$name") {
            header("Authorization", "Bearer $mgmt")
            header("Content-Type", "application/json")
            setBody("""{"args":[]}""")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        val recipe = json.parseToJsonElement(body).jsonObject
        val argv = recipe.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
        val settings = json.parseToJsonElement(argv[argv.indexOf("--settings") + 1]).jsonObject
        val rows = settings.getValue("modelPicker").jsonObject.getValue("options").jsonArray
            .map { it.jsonObject.str("model") }
        return recipe.getValue("env").jsonObject.str("ANTHROPIC_MODEL") to rows
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    /** [block] over the fixture home [home], with Claude Desktop's managed configuration looked for under [tmp], never
     *  the machine's. */
    private fun fixtureHome(home: Path, tmp: Path, block: () -> Unit) =
        UserHome.within(home) { DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json"), block) }

    private fun toml(tmp: Path, anthropicBase: String, xaiBase: String): String {
        val claudeKey = tmp.resolve("claude.json").also { Files.writeString(it, """{"api_key":"sk-fixture"}""") }
        val xaiKey = tmp.resolve("xai.json").also { Files.writeString(it, """{"api_key":"xai-fixture"}""") }
        val missing = tmp.resolve("moon.json")
        return """
            [daemon]
            control_port = $controlPort

            [providers.anthropic]
            dialect = "anthropic-passthrough"
            base_url = "$anthropicBase"
            auth = { kind = "api-key", file = "${claudeKey.esc()}" }

            [providers.xai]
            dialect = "anthropic-passthrough"
            base_url = "$xaiBase"
            auth = { kind = "api-key", file = "${xaiKey.esc()}" }

            [providers.moonshot]
            dialect = "anthropic-passthrough"
            base_url = "$xaiBase"
            auth = { kind = "api-key", file = "${missing.esc()}" }
            [[providers.moonshot.models]]
            id = "k3"
            context_window = 200000

        """.trimIndent() + "\n" + headsToml()
    }

    /** The heads on [toml]'s providers, `moon` on the one with no key, and the two door commands. */
    private fun headsToml(): String {
        return """
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

            [heads.moon]
            provider = "moonshot"
            port = $moonPort
            discovery_prefix = "claude-moon--"
            pinned_model = "k3"
            claude = { command = "claude-moon" }

            [door.commands.acme]
            head = "grok"

            [door.commands.everything]
            head = "grok"
            models = "door"
        """.trimIndent()
    }
}

private fun Path.esc() = toString().replace("\\", "/")
