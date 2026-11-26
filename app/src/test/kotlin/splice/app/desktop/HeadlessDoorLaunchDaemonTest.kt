// NEW: #791 (Nov 23, 2026) — `claude` on the door, end to end on a daemon over a splice.toml with providers and no
// heads (the shape 0.7.0 writes): its launch outlives the providers' wall (the client's timers and its wall header),
// says those timers again in its --settings overlay, and keeps the caller's NO_PROXY beside loopback. Before, the
// door read its wall from heads alone, so a launch on a head-less topology planted no timer at all.
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
import splice.topology.TopologyLoader
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.days

/** An Anthropic Messages provider whose `/v1/models` lists [id]. */
private class HeadlessDoorProvider(id: String) {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val pool = Executors.newCachedThreadPool()
    private val listing = """{"data":[{"id":"$id"}]}""".toByteArray()
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

class HeadlessDoorLaunchDaemonTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()

    @Test
    fun `claude on a door with no heads outlives its providers' wall and keeps the caller's NO_PROXY`(
        @TempDir tmp: Path,
    ) = withDoor(tmp) { mgmt, home -> checkLaunch(launch(mgmt, home)) }

    @Test
    fun `a resume updates its interactive row's labels without listing a headless launch`(@TempDir tmp: Path) =
        withDoor(tmp, ::checkSessionLabels)

    private fun withDoor(tmp: Path, test: suspend (String, Path) -> Unit) {
        val xai = HeadlessDoorProvider("grok-4.7")
        val home = Files.createDirectories(tmp.resolve("home"))
        val fixture = mapOf("XDG_CONFIG_HOME" to tmp.resolve("config").toString())
        UserHome.within(home) {
            DesktopManagedPolicy.within(tmp.resolve("etc/managed-settings.json")) {
                runBlocking {
                    val statePaths = StatePaths(
                        baseOverride = home.resolve(".splice").resolve("state"),
                        envReader = EnvReader { fixture[it] },
                    )
                    val mgmt = MgmtKey(statePaths).get()
                    val daemon = Daemon(
                        topology = TopologyLoader.parse(toml(tmp, xai.baseUrl)),
                        statePaths = statePaths,
                        log = {},
                        refreshCall = { _, _ -> RefreshAttempt.Denied("test-denied") },
                    )
                    daemon.start()
                    try {
                        awaitListening(controlPort)
                        test(mgmt, home)
                    } finally {
                        daemon.stop()
                    }
                }
            }
        }
        xai.stop()
        client.close()
    }

    private fun checkLaunch(recipe: JsonObject) {
        val env = recipe.getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        val wall = env.getValue("API_TIMEOUT_MS").toLong()
        assertTrue(wall > 7.days.inWholeMilliseconds, "the client outlives the longest hold: $wall")
        assertEquals("0", env["API_FORCE_IDLE_TIMEOUT"])
        assertEquals(wall.toString(), env["CLAUDE_STREAM_IDLE_TIMEOUT_MS"])
        val owner = env.getValue("SPLICE_FOREGROUND_OWNER")
        assertEquals(
            listOf("x-splice-client-wall-ms: $wall", "x-splice-launch-owner: $owner"),
            env.getValue("ANTHROPIC_CUSTOM_HEADERS").lines(),
        )
        assertEquals("corp.example,127.0.0.1,localhost", env["NO_PROXY"])
        val argv = recipe.getValue("argv").jsonArray.map { it.jsonPrimitive.content }
        val overlay = json.parseToJsonElement(argv[argv.indexOf("--settings") + 1]).jsonObject
        val overlayEnv = overlay.getValue("env").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        assertEquals(wall.toString(), overlayEnv["API_TIMEOUT_MS"])
        assertEquals("corp.example,127.0.0.1,localhost", overlayEnv["NO_PROXY"])
        assertEquals("claude-xai--grok-4.7", overlayEnv["ANTHROPIC_MODEL"])
    }

    private suspend fun checkSessionLabels(mgmt: String, home: Path) {
        val registry = Files.createDirectories(home.resolve(".claude").resolve("sessions"))
        val pid = ProcessHandle.current().pid()
        Files.writeString(registry.resolve("$pid.json"), """{"pid":$pid,"sessionId":"conversation"}""")
        val first = launchOwner(launch(mgmt, home, "TICKET=ENG-412"))
        val resumed = launchOwner(launch(mgmt, home, "TICKET=ENG-413"))
        val headless = launchOwner(launch(mgmt, home, "TICKET=headless"))

        sessionHook(mgmt, first, "conversation", "startup")
        assertEquals(mapOf("conversation" to """{"ticket":"ENG-412"}"""), sessionLabels(mgmt))
        sessionHook(mgmt, resumed, "conversation", "resume")
        sessionHook(mgmt, headless, "no-registration", "resume")
        sessionHook(mgmt, first, "conversation", "startup")
        assertEquals(mapOf("conversation" to """{"ticket":"ENG-413"}"""), sessionLabels(mgmt))
    }

    private fun launchOwner(recipe: JsonObject): String =
        recipe.getValue("env").jsonObject.getValue("SPLICE_FOREGROUND_OWNER").jsonPrimitive.content

    private suspend fun sessionHook(mgmt: String, owner: String, session: String, source: String) {
        val response = client.post("http://127.0.0.1:$controlPort/hooks/resume/door") {
            header("Authorization", "Bearer $mgmt")
            header("X-Splice-Foreground-Owner", owner)
            header("Content-Type", "application/json")
            setBody("""{"session_id":"$session","source":"$source"}""")
        }
        assertEquals(200, response.status.value, response.bodyAsText())
    }

    private suspend fun sessionLabels(mgmt: String): Map<String, String> {
        val response = client.get("http://127.0.0.1:$controlPort/api/sessions") {
            header("Authorization", "Bearer $mgmt")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }
            .associate { it.getValue("session_id").jsonPrimitive.content to it.getValue("labels").toString() }
    }

    /** The recipe a launch of `claude` answers, asked as the shim asks: a form with the caller's NO_PROXY. */
    private suspend fun launch(mgmt: String, home: Path, label: String? = null): JsonObject {
        val fields = listOf(
            "cwd" to home.toString(),
            "noProxy" to "corp.example",
            "dangerouslySkipPermissions" to "no",
        ) + listOfNotNull(label?.let { "label" to it })
        val form = fields
            .joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        val response = client.post("http://127.0.0.1:$controlPort/launch/claude") {
            header("Authorization", "Bearer $mgmt")
            header("Content-Type", "application/x-www-form-urlencoded")
            setBody(form)
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject
    }

    private fun toml(tmp: Path, xaiBase: String): String {
        val xaiKey = tmp.resolve("xai.json").also { Files.writeString(it, """{"api_key":"xai-fixture"}""") }
        return """
            [daemon]
            control_port = $controlPort

            [providers.xai]
            dialect = "anthropic-passthrough"
            base_url = "$xaiBase"
            auth = { kind = "api-key", file = "${xaiKey.toString().replace("\\", "/")}" }
            pinned_model = "grok-4.7"
        """.trimIndent()
    }
}
