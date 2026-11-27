// NEW: #791 (Nov 23, 2026) — `claude` on the door, end to end on a daemon over a splice.toml with providers and no
// heads (the shape 0.7.0 writes): its launch outlives the providers' wall (the client's timers and its wall header),
// says those timers again in its --settings overlay, and keeps the caller's NO_PROXY beside loopback. Before, the
// door read its wall from heads alone, so a launch on a head-less topology planted no timer at all.
package splice.app.desktop

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
import splice.core.client.LAUNCH_OWNER_HEADER
import splice.core.client.SESSION_HEADER
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.UserHome
import splice.core.testing.TestPorts
import splice.core.util.EnvReader
import splice.head.awaitListening
import splice.topology.TopologyLoader
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.days

private const val SESSION = "a934dca9-8a01-4dd2-aca0-1c976892b51e"

class HeadlessDoorLaunchDaemonTest {
    private val client = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }
    private val controlPort = TestPorts.reserve()

    @Test
    fun `claude on a door with no heads outlives its providers' wall and keeps the caller's NO_PROXY`(
        @TempDir tmp: Path,
    ) = withDoor(tmp) { mgmt, home -> checkLaunch(launch(mgmt, home)) }

    @Test
    fun `a served resume updates its registry row labels without adding a headless twin`(@TempDir tmp: Path) =
        withDoor(tmp, ::checkSessionLabels)

    @Test
    fun `a headless launch with no installed mods appears in both session listings after a turn`(
        @TempDir tmp: Path,
    ) = withDoor(tmp) { mgmt, home ->
        val pid = ProcessHandle.current().pid()
        val recipe = launch(mgmt, home, "ROLE=worker", pid)
        turn(recipe, SESSION, launchOwner(recipe))

        val row = sessionRows(mgmt).single()
        assertEquals(SESSION, row.field("session_id"))
        assertEquals(SESSION, row.field("id"))
        assertEquals("headless", row.field("kind"))
        assertEquals("live", row.field("availability"))
        assertEquals(pid.toString(), row.field("pid"))
        assertEquals(home.toString(), row.field("cwd"))
        assertEquals("""{"role":"worker"}""", row.getValue("labels").toString())
        assertEquals(row, sessionRows(mgmt, "?kind=all").single())
    }

    @Test
    fun `a start hook without a turn and a turn without a launch owner make no row`(@TempDir tmp: Path) =
        withDoor(tmp) { mgmt, home ->
            val recipe = launch(mgmt, home, "ROLE=worker", ProcessHandle.current().pid())
            sessionHook(mgmt, launchOwner(recipe), SESSION, "resume")
            turn(recipe, "261ceb22-e3c4-4e43-840a-c89ae91954ca", owner = null)

            assertEquals(emptyList<JsonObject>(), sessionRows(mgmt))
        }

    @Test
    fun `a headless id stays live when the newest serving process exits first`(@TempDir tmp: Path) =
        withDoor(tmp) { mgmt, home ->
            val first = ProcessBuilder("sleep", "60").start()
            val second = ProcessBuilder("sleep", "60").start()
            try {
                val firstRecipe = launch(mgmt, home, "ROLE=first", first.pid())
                val secondRecipe = launch(mgmt, home, "ROLE=second", second.pid())
                turn(firstRecipe, SESSION, launchOwner(firstRecipe))
                turn(secondRecipe, SESSION, launchOwner(secondRecipe))
                assertEquals(second.pid().toString(), sessionRows(mgmt).single().field("pid"))

                stop(second)
                val live = sessionRows(mgmt).single()
                assertEquals("live", live.field("availability"))
                assertEquals(first.pid().toString(), live.field("pid"))
                assertEquals("""{"role":"first"}""", live.getValue("labels").toString())

                stop(first)
                assertEquals("gone", sessionRows(mgmt).single().field("availability"))
            } finally {
                stop(first)
                stop(second)
            }
        }

    private fun stop(process: Process) {
        process.destroyForcibly().waitFor()
    }

    private fun withDoor(tmp: Path, test: suspend (String, Path) -> Unit) {
        val xai = FormatProvider(Format.MESSAGES, "grok-4.7", "fixture answer")
        val home = Files.createDirectories(tmp.resolve("home"))
        val fixture = mapOf("XDG_CONFIG_HOME" to tmp.resolve("config").toString())
        try {
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
        } finally {
            xai.stop()
            client.close()
        }
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
        val first = launchOwner(launch(mgmt, home, "TICKET=ENG-412", pid))
        sessionHook(mgmt, first, "conversation", "startup")
        assertEquals("""{"ticket":"ENG-412"}""", sessionRows(mgmt).single().getValue("labels").toString())

        val recipe = launch(mgmt, home, "TICKET=ENG-413", pid)
        val resumed = launchOwner(recipe)
        sessionHook(mgmt, resumed, "conversation", "resume")
        turn(recipe, "conversation", resumed)
        sessionHook(mgmt, first, "conversation", "startup")
        val row = sessionRows(mgmt).single()
        assertEquals("conversation", row.field("session_id"))
        assertEquals("""{"ticket":"ENG-413"}""", row.getValue("labels").toString())
        assertTrue(row["kind"]?.jsonPrimitive?.content != "headless", row.toString())
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

    private suspend fun turn(recipe: JsonObject, session: String, owner: String?) {
        val env = recipe.getValue("env").jsonObject
        val token = env.field("ANTHROPIC_AUTH_TOKEN")
        val model = env.field("ANTHROPIC_MODEL")
        val response = client.post("http://127.0.0.1:$controlPort/door/v1/messages") {
            header("Authorization", "Bearer $token")
            header("Content-Type", "application/json")
            header(SESSION_HEADER, session)
            if (owner != null) header(LAUNCH_OWNER_HEADER, owner)
            setBody(
                """{"model":"$model","max_tokens":16,"stream":false,""" +
                    """"messages":[{"role":"user","content":"hello"}]}""",
            )
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        assertTrue("fixture answer" in body, body)
    }

    private suspend fun sessionRows(mgmt: String, query: String = ""): List<JsonObject> {
        val response = client.get("http://127.0.0.1:$controlPort/api/sessions$query") {
            header("Authorization", "Bearer $mgmt")
        }
        val body = response.bodyAsText()
        assertEquals(200, response.status.value, body)
        return json.parseToJsonElement(body).jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }
    }

    private fun JsonObject.field(key: String) = getValue(key).jsonPrimitive.content

    /** The recipe the daemon answers, asked as the shim asks: a form with the caller's NO_PROXY. */
    private suspend fun launch(mgmt: String, home: Path, label: String? = null, pid: Long? = null): JsonObject {
        val fields = listOf(
            "cwd" to home.toString(),
            "noProxy" to "corp.example",
            "dangerouslySkipPermissions" to "no",
        ) + listOfNotNull(label?.let { "label" to it }, pid?.let { "launch" to it.toString() })
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
