// `splice desktop on` sets Claude Desktop up itself, end to end on a daemon over a fixture home: splice's configuration
// in Desktop's 3P library, applied, naming a credential helper that prints the desktop key, with no key in the library
// and nothing changed in the terminal's settings; Desktop's requests (the key as x-api-key, the root probe, discovery)
// answered; and `off` putting the library back as it was, the configuration the person had applied included.
package splice.app.desktop

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
import java.util.concurrent.TimeUnit

/** An Anthropic Messages provider that lists one model. */
private class OneModelProvider {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    val baseUrl get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = pool
        server.createContext("/") { ex ->
            val _ = ex.requestBody.readBytes()
            val body = """{"data":[{"id":"claude-opus-5-5","display_name":"Claude Opus 5.5"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun stop() {
        server.stop(0)
        pool.shutdownNow()
    }
}

class DesktopSetupDaemonTest {
    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()
    private val headPort = TestPorts.reserve()

    @Test
    fun `on sets Desktop up with nothing to paste, and off puts back exactly what was there`(@TempDir tmp: Path) {
        val home = Files.createDirectories(tmp.resolve("home"))
        val settings = Files.createDirectories(home.resolve(".claude")).resolve("settings.json")
        Files.writeString(settings, """{"model":"claude-opus-5-5","env":{"FOO":"1"}}""")
        val library = Files.createDirectories(tmp.resolve("config/Claude-3p/configLibrary"))
        // The person's own configuration, applied, which `on` stands in for and `off` puts back.
        val theirs = "0b7e3c1a-1111-4c2d-9e8f-123456789abc"
        val meta = """{"appliedId":"$theirs","entries":[{"id":"$theirs","name":"Work Bedrock"}]}"""
        Files.writeString(library.resolve("_meta.json"), meta)
        Files.writeString(library.resolve("$theirs.json"), """{"inferenceProvider":"bedrock"}""")
        val xdg = tmp.resolve("config").toString()
        // The daemon's environment: Desktop's configuration under the fixture home, never the person's.
        val fixture = mapOf(
            "XDG_CONFIG_HOME" to xdg,
        )
        val fixtureEnv = EnvReader { fixture[it] }
        val provider = OneModelProvider()
        fixtureHome(home, tmp) {
            runBlocking {
                val statePaths = StatePaths(baseOverride = tmp.resolve("state"), envReader = fixtureEnv)
                val mgmt = MgmtKey(statePaths).get()
                val key = tmp.resolve("anthropic.json").also { Files.writeString(it, """{"api_key":"sk-fixture"}""") }
                val daemon = Daemon(
                    topology = TopologyLoader.parse(toml(provider.baseUrl, key)),
                    statePaths = statePaths,
                    log = {},
                    refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
                )
                daemon.start()
                try {
                    awaitListening(controlPort, headPort)
                    on(mgmt, library, theirs, statePaths)
                    desktopAsks(statePaths, mgmt)
                    off(mgmt, library, meta, theirs, statePaths)
                } finally {
                    daemon.stop()
                }
            }
        }
        provider.stop()
        client.close()
        assertEquals("""{"model":"claude-opus-5-5","env":{"FOO":"1"}}""", Files.readString(settings))
    }

    private suspend fun on(mgmt: String, library: Path, theirs: String, statePaths: StatePaths) {
        val answer = post(mgmt, "/api/desktop/on")
        assertTrue(answer.str("said").contains("It replaces Work Bedrock while splice is on"), answer.toString())
        val meta = json.parseToJsonElement(Files.readString(library.resolve("_meta.json"))).jsonObject
        val ours = meta.str("appliedId")
        assertTrue(ours != theirs)
        assertEquals(listOf(theirs, ours), meta.getValue("entries").jsonArray.map { it.jsonObject.str("id") })
        val written = json.parseToJsonElement(Files.readString(library.resolve("$ours.json"))).jsonObject
        assertEquals("gateway", written.str("inferenceProvider"))
        assertEquals("http://127.0.0.1:$controlPort/door", written.str("inferenceGatewayBaseUrl"))
        assertEquals("""{"x-splice-desktop":"app"}""", written.getValue("inferenceCustomHeaders").toString())
        // No key anywhere in the library: the helper Desktop runs prints it.
        val desktopKey = DesktopKey(statePaths.desktopKeyFile) {}.get()
        Files.list(library).use { files -> files.forEach { assertFalse(desktopKey in Files.readString(it), "$it") } }
        val helper = Path.of(written.str("inferenceCredentialHelper"))
        assertTrue(helper.isAbsolute && Files.isExecutable(helper))
        val run = ProcessBuilder(helper.toString()).redirectErrorStream(false).start()
        val printed = run.inputStream.bufferedReader().readText().trim()
        assertTrue(run.waitFor(60, TimeUnit.SECONDS))
        assertEquals(desktopKey, printed)
    }

    /** Claude Desktop's own requests: the root probe, and discovery with the key as x-api-key in app mode. */
    private suspend fun desktopAsks(statePaths: StatePaths, mgmt: String) {
        val root = client.get("http://127.0.0.1:$controlPort/")
        assertEquals(200, root.status.value)
        assertEquals("splice", root.bodyAsText())
        val browser = client.get("http://127.0.0.1:$controlPort/") { header("Accept", "text/html") }
        assertEquals(302, browser.status.value)
        assertEquals("app", get(mgmt, "/api/desktop").str("mode"))
        assertEquals("false", get(mgmt, "/api/desktop").str("relaunched"))
        val desktopKey = DesktopKey(statePaths.desktopKeyFile) {}.get()
        val listed = client.get("http://127.0.0.1:$controlPort/door/v1/models") {
            header("x-api-key", desktopKey)
            header("x-splice-desktop", "app")
        }
        assertEquals(200, listed.status.value, listed.bodyAsText())
        val ids = json.parseToJsonElement(listed.bodyAsText()).jsonObject.getValue("data").jsonArray
            .map { it.jsonObject.str("id") }
        assertEquals(listOf("claude-opus-5-5"), ids)
        val state = get(mgmt, "/api/desktop")
        assertEquals("true", state.str("relaunched"))
        assertEquals("true", state.str("picker_current"))
        features(state)
    }

    /** What of Desktop works on splice, by mode (df-features): what Desktop turns off in app mode is named with
     *  Desktop as the reason, SSH is out because the door is loopback-only, an Anthropic head answers the server web
     *  tools, and a key head that reports no plan windows is named for the usage meter. */
    private fun features(state: JsonObject) {
        val features = state.getValue("features").jsonArray.map { it.jsonObject }
        fun of(name: String, mode: String) = features.single { it.str("name") == name && it.str("mode") == mode }
        assertEquals(28, features.size, "fourteen features in each of the two modes")
        assertEquals("unavailable", of("fast mode", "app").str("state"))
        assertTrue(of("fast mode", "app").str("reason").contains("Desktop"))
        assertTrue(of("SSH sessions", "standard").str("reason").contains("127.0.0.1"))
        assertEquals("works", of("web search and fetch", "app").str("state"))
        assertEquals("unavailable", of("usage display", "app").str("state"))
        assertTrue(of("usage display", "app").str("reason").startsWith("no plan windows come from anthropic,"))
    }

    private suspend fun off(mgmt: String, library: Path, meta: String, theirs: String, statePaths: StatePaths) {
        val answer = post(mgmt, "/api/desktop/off")
        assertTrue(answer.str("said").contains("taken out of Claude Desktop"), answer.toString())
        assertEquals(meta, Files.readString(library.resolve("_meta.json")))
        val left = Files.list(library).use { files -> files.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("$theirs.json", "_meta.json").sorted(), left)
        assertFalse(Files.exists(statePaths.stateDir.resolve("desktop-credential")))
        // Their own configuration is applied again, so Desktop opens on it, not on splice.
        val state = get(mgmt, "/api/desktop")
        assertEquals("other", state.str("mode"))
        assertEquals("Work Bedrock", state.str("other"))
    }

    private suspend fun post(key: String, path: String): JsonObject {
        val response = client.post("http://127.0.0.1:$controlPort$path") {
            header("Authorization", "Bearer $key")
            setBody("{}")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject
    }

    private suspend fun get(key: String, path: String): JsonObject {
        val response = client.get("http://127.0.0.1:$controlPort$path") { header("Authorization", "Bearer $key") }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    /** [block] over the fixture home [home], with Claude Desktop's managed configuration looked for under [tmp], never
     *  the machine's. */
    private fun fixtureHome(home: Path, tmp: Path, block: () -> Unit) =
        UserHome.within(home) { DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json"), block) }

    private fun toml(base: String, key: Path): String = """
        [daemon]
        control_port = $controlPort

        [providers.anthropic]
        dialect = "anthropic-passthrough"
        base_url = "$base"
        auth = { kind = "api-key", file = "${key.toString().replace("\\", "/")}" }
        [[providers.anthropic.models]]
        id = "claude-opus-5-5"
        context_window = 200000

        [heads.anthropic]
        provider = "anthropic"
        port = $headPort
        discovery_prefix = "claude-anthropic--"
        pinned_model = "claude-opus-5-5"
        claude = { command = "claude-anthropic" }
    """.trimIndent()
}
