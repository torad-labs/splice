// NEW: V4-220 item 3 — `splice add` from the console, over /api/add. Driven end to end: the real control
// server under the bearer, the real candidate/checks/write, the real key store PUT /api/keys writes, a
// local endpoint the checks reach over HTTP, and a head whose gate reports what V4-213's live rows carry,
// so the save's restart can be seen waiting for a compaction exactly as the console's restart button does.
// The config and the key store sit in a temp dir SPLICE_CONFIG names; no operator file is read.
package splice.app.control.api.fleet

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.accounts.signin.LoginState
import splice.accounts.signin.LoginStatus
import splice.app.control.ControlServer
import splice.app.control.ManagedHead
import splice.configuration.add.AddConsole
import splice.configuration.add.AddSignIn
import splice.configuration.add.WrapperInstall
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.KeyStore
import splice.core.config.KeyStorePath
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.GateHealth
import splice.core.head.GatePhase
import splice.core.head.GateSlot
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.terminal.TerminalOutput
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.util.EnvReader
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.lifecycle.restart.DaemonSupervised
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val SETTLE_MS = 1_500L
private const val HEAD_KEY = "claudex"
private const val TWO_MINUTES_MS = 120_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AddBackendRouteTest {

    private val url: String get() = "http://127.0.0.1:${control.listeningPort}"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private val drains = AtomicInteger()
    private val logged = CopyOnWriteArrayList<String>()
    private val linked = CopyOnWriteArrayList<String>()
    private val signIns = CopyOnWriteArrayList<Triple<String, ProviderConfig, Topology>>()
    private val vars = ConcurrentHashMap<String, String>()
    private lateinit var control: ControlServer
    private lateinit var key: String
    private lateinit var endpoint: HttpServer
    private lateinit var tmp: Path

    /** What the head's gate reports as in flight right now; null is an idle gate. */
    @Volatile private var inFlight: GateSlot? = null

    private val env = EnvReader { vars[it] }
    private val config: Path get() = Path.of(vars.getValue("SPLICE_CONFIG"))

    @BeforeAll
    fun setUp() {
        tmp = Files.createTempDirectory("add-backend")
        endpoint = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1") { exchange ->
                val body = if (exchange.requestURI.path == "/v1/models") """{"data":[{"id":"m"}]}""" else "{}"
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
            start()
        }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { logged += it },
            shutdownDaemon = { drains.incrementAndGet() },
        )
        control.ports.add = AddConsole(FakeSignIn(), WrapperInstall { k, _ -> linked.add(k) }, env, TerminalOutput { })
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        endpoint.stop(0)
        client.close()
    }

    @BeforeEach
    fun reset() {
        inFlight = null
        drains.set(0)
        logged.clear()
        linked.clear()
        signIns.clear()
        vars.clear()
        vars["SPLICE_CONFIG"] = Files.createTempDirectory(tmp, "config").resolve("splice.toml").toString()
        control.ports.keys = KeyStore(KeyStorePath.defaultPath(env))
        control.ports.supervised = DaemonSupervised { true }
    }

    private val fw: String
        get() = """{"profile":"api-key","name":"fw","base_url":"http://127.0.0.1:${endpoint.address.port}/v1",""" +
            """"models":[{"id":"m","context_window":1000}]}"""

    @Test
    fun `the profiles are the catalogue, each with what the operator must still supply`() = runBlocking {
        val response = get("/api/add/profiles")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val profiles = body(response)["profiles"]!!.jsonArray.map { it.jsonObject }
        val names = profiles.map { it["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("codex", "grok", "claude", "muse", "api-key")), "$names")
        val generic = profiles.single { it["name"]!!.jsonPrimitive.content == "api-key" }
        assertEquals(listOf("name", "base_url", "models"), generic["asks"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** RED before V4-220 item 3: POST /api/add answered 404; the console could not add a backend. */
    @Test
    fun `an api-key backend signs in through the key store and saves through the restart's compaction wait`() =
        runBlocking {
            val opened = post("/api/add", fw)
            assertEquals(HttpStatusCode.OK, opened.status, opened.bodyAsText())
            val view = body(opened)
            val id = view["id"]!!.jsonPrimitive.content
            assertEquals("fw", view["key"]!!.jsonPrimitive.content)
            assertEquals("key", view["sign_in_by"]!!.jsonPrimitive.content)
            assertEquals("FW_API_KEY", view["key_env"]!!.jsonPrimitive.content)
            assertEquals("false", view["credential"]!!.jsonObject["present"]!!.jsonPrimitive.content)

            val login = post("/api/add/$id/login")
            assertEquals(HttpStatusCode.Conflict, login.status)
            assertEquals("'fw' reads its key from FW_API_KEY: set it on the Keys page, then verify.", error(login))

            val unkeyed = post("/api/add/$id/verify")
            assertEquals(HttpStatusCode.Conflict, unkeyed.status, unkeyed.bodyAsText())
            assertEquals("The credential check failed: no credential for 'fw' (api-key).", error(unkeyed))

            assertEquals(HttpStatusCode.OK, put("/api/keys/FW_API_KEY", """{"value":"k"}""").status)
            val verified = post("/api/add/$id/verify")
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            val rows = body(verified)["checks"]!!.jsonArray.map { it.jsonObject["ok"]!!.jsonPrimitive.content }
            assertEquals(listOf("true", "true", "true"), rows)

            inFlight = GateSlot("b2e4d8f1 gpt-6-sol", true, GatePhase.STREAMING, TWO_MINUTES_MS, 0)
            val saved = post("/api/add/$id/save")
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val result = body(saved)["saved"]!!.jsonObject
            assertEquals("true", result["wrapper"]!!.jsonObject["linked"]!!.jsonPrimitive.content)
            assertEquals(listOf("fw"), linked)
            val restart = result["restart"]!!.jsonObject
            assertEquals("waiting", restart["status"]!!.jsonPrimitive.content, saved.bodyAsText())
            val waitedOn = restart["compactions"]!!.jsonArray.single().jsonObject
            assertEquals(HEAD_KEY, waitedOn["head"]!!.jsonPrimitive.content)
            val written = Files.readString(config)
            assertTrue("[providers.fw]" in written && "[heads.fw]" in written, written)

            // The same restart the console's button takes: its status shows the add's wait, and the drain
            // follows the compaction, not the save.
            assertEquals(0, drainsAfter(SETTLE_MS), "the save drained while a compaction was in flight")
            val phase = body(get("/api/daemon/restart"))["status"]!!.jsonPrimitive.content
            assertEquals("waiting", phase, "the save's restart is not the console restart's")
            inFlight = null
            assertEquals(1, drainsAfter(TIMEOUT_MS), "the drain follows the compaction's end")

            val again = post("/api/add/$id/save")
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals("'fw' is already saved.", error(again))
            val line = logged.singleOrNull { it.startsWith("[control] add fw: saved to ") }
            assertTrue(line?.endsWith("; restart waiting\n") == true, "$logged")
        }

    @Test
    fun `a save with nothing to wait for answers, then drains`() = runBlocking {
        vars["FW_API_KEY"] = "k"
        val id = body(post("/api/add", fw))["id"]!!.jsonPrimitive.content
        val saved = post("/api/add/$id/save")
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val restart = body(saved)["saved"]!!.jsonObject["restart"]!!.jsonObject
        assertEquals("draining", restart["status"]!!.jsonPrimitive.content)
        assertEquals(1, drainsAfter(TIMEOUT_MS))
    }

    @Test
    fun `a daemon nothing restarts saves the head and says the restart was refused`() = runBlocking {
        vars["FW_API_KEY"] = "k"
        control.ports.supervised = DaemonSupervised { false }
        val id = body(post("/api/add", fw))["id"]!!.jsonPrimitive.content
        val saved = post("/api/add/$id/save")
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        val restart = body(saved)["saved"]!!.jsonObject["restart"]!!.jsonObject
        assertEquals("refused", restart["status"]!!.jsonPrimitive.content)
        assertTrue(restart["error"]!!.jsonPrimitive.content.startsWith("nothing will restart this daemon"), "$restart")
        assertEquals(0, drainsAfter(SETTLE_MS))
        assertTrue("[heads.fw]" in Files.readString(config))
    }

    /** The CLI's reread-then-rename (AddWrite): an edit made while the add was open is never overwritten. */
    @Test
    fun `a file changed under an open add refuses the save and keeps the edit`() = runBlocking {
        vars["FW_API_KEY"] = "k"
        val id = body(post("/api/add", fw))["id"]!!.jsonPrimitive.content
        val edited = Files.readString(config) + "\n# edited meanwhile\n"
        Files.writeString(config, edited)
        val saved = post("/api/add/$id/save")
        assertEquals(HttpStatusCode.Conflict, saved.status, saved.bodyAsText())
        assertTrue(error(saved).endsWith("changed while this add was running — rerun"), error(saved))
        assertEquals(edited, Files.readString(config))
        assertEquals(0, drainsAfter(SETTLE_MS))
    }

    /** Two console forms open on one file: the first save's tables are never renamed over by the second. */
    @Test
    fun `a second form saved after the first refuses instead of dropping the first's head`() = runBlocking {
        vars["FW_API_KEY"] = "k"
        vars["FX_API_KEY"] = "k"
        val first = body(post("/api/add", fw))["id"]!!.jsonPrimitive.content
        val fx = fw.replace("\"name\":\"fw\"", "\"name\":\"fx\"")
        val second = body(post("/api/add", fx))["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, post("/api/add/$first/save").status)
        val late = post("/api/add/$second/save")
        assertEquals(HttpStatusCode.Conflict, late.status, late.bodyAsText())
        assertTrue(error(late).endsWith("changed while this add was running — rerun"), error(late))
        val written = Files.readString(config)
        assertTrue("[heads.fw]" in written && "[heads.fx]" !in written, written)
    }

    @Test
    fun `an oauth backend signs in with its candidate's own provider and topology`() = runBlocking {
        val id = body(post("/api/add", """{"profile":"codex","name":"cx"}"""))["id"]!!.jsonPrimitive.content
        val first = post("/api/add/$id/login")
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val signIn = body(first)["sign_in"]!!.jsonObject
        assertEquals("waiting", signIn["state"]!!.jsonPrimitive.content)
        val (started, provider, topology) = signIns.single()
        assertEquals("cx", started)
        assertEquals("chatgpt-oauth", provider.auth.kind)
        assertTrue("cx" in topology.heads, "the flow must see the head the add would write")
        val second = body(post("/api/add/$id/login"))["sign_in"]!!.jsonObject
        assertEquals(signIn["id"], second["id"], "a second click answers the running flow")
        assertEquals(1, signIns.size)
    }

    @Test
    fun `refusals carry their own sentence and the status that fits`() = runBlocking {
        assertEquals(HttpStatusCode.NotFound, post("/api/add", """{"profile":"nope"}""").status)
        val bare = post("/api/add", "{}")
        assertEquals(HttpStatusCode.BadRequest, bare.status)
        assertEquals("Name the profile to add.", error(bare))
        val taken = post("/api/add", """{"profile":"openrouter"}""")
        assertEquals(HttpStatusCode.Conflict, taken.status, taken.bodyAsText())
        assertEquals("'openrouter' is already configured — pick another --name", error(taken))
        val forwarded = post("/api/add", """{"profile":"claude"}""")
        val login = post("/api/add/${body(forwarded)["id"]!!.jsonPrimitive.content}/login")
        assertEquals(HttpStatusCode.Conflict, login.status)
        val forwardedLogin = "'claude-splice' has no sign-in of its own: your Claude login is forwarded at launch."
        assertEquals(forwardedLogin, error(login))
        assertEquals(HttpStatusCode.NotFound, get("/api/add/no-such-id").status)
        val stranger = withTimeout(TIMEOUT_MS) { client.post("$url/api/add") { setBody(fw) } }
        assertEquals(HttpStatusCode.Unauthorized, stranger.status)
    }

    /** Records the flow a login would start and announces it waiting, as a browser flow does. */
    private inner class FakeSignIn : AddSignIn {
        private val statuses = mutableMapOf<String, LoginStatus>()

        override fun start(key: String, provider: ProviderConfig, topology: Topology): LoginStatus {
            signIns += Triple(key, provider, topology)
            val status = LoginStatus("login-${signIns.size}", key, LoginState.WAITING, browserUrl = "https://auth")
            statuses[status.id] = status
            return status
        }

        override fun poll(id: String): LoginStatus? = statuses[id]
    }

    private fun body(response: HttpResponse): JsonObject =
        runBlocking { json.parseToJsonElement(response.bodyAsText()).jsonObject }

    private fun error(response: HttpResponse): String = body(response)["error"]!!.jsonPrimitive.content

    private suspend fun drainsAfter(ms: Long): Int {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline && drains.get() == 0) delay(POLL_MS)
        return drains.get()
    }

    private suspend fun post(path: String, body: String? = null): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.post("$url$path") {
            header("Authorization", "Bearer $key")
            body?.let { setBody(it) }
        }
    }

    private suspend fun put(path: String, body: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.put("$url$path") {
            header("Authorization", "Bearer $key")
            setBody(body)
        }
    }

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        awaitPort()
        client.get("$url$path") { header("Authorization", "Bearer $key") }
    }

    private fun managedHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD_KEY
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth {
                val live = listOfNotNull(inFlight)
                return HeadHealth(true, true, port, "test", gate = GateHealth(inflight = live.size, live = live))
            }
        },
        auth = object : AuthProvider {
            override suspend fun credentials() = null
            override suspend fun describe() = AuthDescription(false, "test", emptyMap())
        },
        usage = HeadUsageSource { UsageView(0, 0, RateLimitView(null, null, null)) },
        compact = object : HeadCompactSource {
            override fun summary(tailN: Int): CompactView = CompactView(0, emptyMap(), emptyList())
        },
        logs = object : HeadLogSource {
            override fun tail(lines: Int): String = ""
            override fun path(): String = ""
        },
        warnPct = 80,
        warnTokens5h = 0,
    )

    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (runCatching { ServerSocket(control.listeningPort).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :${control.listeningPort}")
    }
}
