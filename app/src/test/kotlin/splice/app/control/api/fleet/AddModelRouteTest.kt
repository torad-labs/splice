// NEW: V4-220 — `splice add-model` from the console, over /api/add-model. Driven end to end: the real
// control server under the bearer, the real offers, roster edit and write, on the starter TopologyLoader
// materializes in a temp dir SPLICE_CONFIG names; no operator file is read. What is on offer is checked
// against a denominator the route does not compute: GET /api/add/profiles' OpenRouter rows minus the
// roster the loader parses back.
package splice.app.control.api.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.accounts.signin.LoginStatus
import splice.app.control.ControlServer
import splice.configuration.add.AddConsole
import splice.configuration.add.AddSignIn
import splice.configuration.add.WrapperInstall
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.terminal.TerminalOutput
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.lifecycle.restart.DaemonSupervised
import splice.topology.TopologyLoader
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val SETTLE_MS = 1_000L
private const val HEAD = "openrouter"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AddModelRouteTest {

    private val url: String get() = "http://127.0.0.1:${control.listeningPort}"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val drains = AtomicInteger()
    private val logged = CopyOnWriteArrayList<String>()
    private val vars = ConcurrentHashMap<String, String>()
    private lateinit var control: ControlServer
    private lateinit var adds: AddConsole
    private lateinit var key: String
    private lateinit var tmp: Path

    private val config: Path get() = Path.of(vars.getValue("SPLICE_CONFIG"))

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("add-model")
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { logged += it },
            shutdownDaemon = { drains.incrementAndGet() },
        )
        adds = AddConsole(NoSignIn(), WrapperInstall { _, _ -> true }, EnvReader { vars[it] }, TerminalOutput { })
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @BeforeEach
    fun reset() {
        drains.set(0)
        logged.clear()
        vars.clear()
        vars["SPLICE_CONFIG"] = Files.createTempDirectory(tmp, "config").resolve("splice.toml").toString()
        TopologyLoader.loadOrMaterialize(config)
        control.ports.add = adds
        control.ports.supervised = DaemonSupervised { true }
    }

    /** RED before V4-220: GET and POST /api/add-model answered 404; the console could not add a model. */
    @Test
    fun `the catalogue rows a roster lacks are offered, and one added reaches the roster before the drain`() =
        runBlocking {
            val listed = get("/api/add-model")
            assertEquals(HttpStatusCode.OK, listed.status, listed.bodyAsText())
            val head = body(listed)["heads"]!!.jsonArray.single().jsonObject
            assertEquals(HEAD, head["head"]!!.jsonPrimitive.content)
            val offered = ids(head)
            assertTrue(offered.isNotEmpty(), "the starter's roster already reaches every catalogue row")
            assertEquals(catalogue() - roster().toSet(), offered)

            val pick = offered.first()
            val added = post("/api/add-model", """{"head":"$HEAD","models":["$pick"]}""")
            assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
            val answer = body(added)
            assertEquals(listOf(pick), answer["added"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("draining", answer["restart"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertTrue(pick in roster(), "the added id never reached the head's roster")
            assertEquals(1, drainsAfter(TIMEOUT_MS), "the restart that makes the roster live was not taken")
            assertFalse(pick in ids(body(get("/api/add-model"))["heads"]!!.jsonArray.single().jsonObject))
            val line = "[control] add-model $HEAD: $pick to $config; restart draining\n"
            assertEquals(listOf(line), logged.filter { it.startsWith("[control] add-model") })
        }

    @Test
    fun `an id already on the roster is refused and the file is kept`() = runBlocking {
        val before = Files.readString(config)
        val rostered = roster().first()
        val refused = post("/api/add-model", """{"head":"$HEAD","models":["$rostered"]}""")
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        val text = "'$rostered' is not on offer for '$HEAD': it is on its roster already, or not in the catalogue."
        assertEquals(text, error(refused))
        assertEquals(before, Files.readString(config))
        assertEquals(0, drainsAfter(SETTLE_MS))
    }

    @Test
    fun `a file that does not load is named, and nothing is written or restarted`() = runBlocking {
        Files.writeString(config, "[heads.openrouter\n")
        val listed = get("/api/add-model")
        assertEquals(HttpStatusCode.Conflict, listed.status, listed.bodyAsText())
        assertTrue(error(listed).startsWith("$config does not load ("), error(listed))
        val refused = post("/api/add-model", """{"head":"$HEAD","models":["x"]}""")
        assertEquals(HttpStatusCode.Conflict, refused.status, refused.bodyAsText())
        assertTrue(error(refused).endsWith("so nothing was saved."), error(refused))
        assertEquals("[heads.openrouter\n", Files.readString(config))
        assertEquals(0, drainsAfter(SETTLE_MS))
    }

    @Test
    fun `refusals carry their own sentence and the status that fits`() = runBlocking {
        val cases = mapOf(
            "{}" to (HttpStatusCode.BadRequest to "Name the head to add models to."),
            """{"head":"$HEAD"}""" to (HttpStatusCode.BadRequest to "List the models to add by id."),
            """{"head":"$HEAD","models":[1]}""" to (HttpStatusCode.BadRequest to "List the models to add by id."),
            """{"head":"$HEAD","models":[]}""" to (HttpStatusCode.BadRequest to "Pick at least one model to add."),
            """{"head":"nope","models":["x"]}""" to
                (HttpStatusCode.NotFound to "splice.toml has no OpenRouter head named 'nope'."),
        )
        for ((request, expected) in cases) {
            val response = post("/api/add-model", request)
            assertEquals(expected.first, response.status, request)
            assertEquals(expected.second, error(response), request)
        }
        val stranger = withTimeout(TIMEOUT_MS) { client.post("$url/api/add-model") { setBody("{}") } }
        assertEquals(HttpStatusCode.Unauthorized, stranger.status)
        assertEquals(HttpStatusCode.Unauthorized, withTimeout(TIMEOUT_MS) { client.get("$url/api/add-model") }.status)
        control.ports.add = null
        val unwired = get("/api/add-model")
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
        assertEquals("Adding models is not wired on this daemon.", error(unwired))
        assertEquals(0, drainsAfter(SETTLE_MS))
    }

    /** The OpenRouter catalogue as the add's own profile list serves it: the route is not asked. */
    private suspend fun catalogue(): List<String> {
        val profiles = body(get("/api/add/profiles"))["profiles"]!!.jsonArray.map { it.jsonObject }
        val openrouter = profiles.single { it["name"]!!.jsonPrimitive.content == HEAD }
        return ids(openrouter)
    }

    /** The head's roster as the loader parses the file back. */
    private fun roster(): List<String> =
        requireNotNull(TopologyLoader.parse(Files.readString(config)).heads[HEAD]?.models) { "no roster" }.map { it.id }

    private fun ids(entry: JsonObject): List<String> =
        entry["models"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    /** No add here signs in: add-model never starts a flow. */
    private class NoSignIn : AddSignIn {
        override fun start(key: String, provider: ProviderConfig, topology: Topology): LoginStatus =
            error("add-model started a sign-in")

        override fun poll(id: String): LoginStatus? = null
    }

    private fun body(response: HttpResponse): JsonObject =
        runBlocking { json.parseToJsonElement(response.bodyAsText()).jsonObject }

    private fun error(response: HttpResponse): String = body(response)["error"]!!.jsonPrimitive.content

    private suspend fun drainsAfter(ms: Long): Int {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline && drains.get() == 0) delay(POLL_MS)
        return drains.get()
    }

    private suspend fun post(path: String, body: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.post("$url$path") {
            header("Authorization", "Bearer $key")
            setBody(body)
        }
    }

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.get("$url$path") { header("Authorization", "Bearer $key") }
    }

    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { ServerSocket(control.listeningPort).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :${control.listeningPort}")
    }
}
