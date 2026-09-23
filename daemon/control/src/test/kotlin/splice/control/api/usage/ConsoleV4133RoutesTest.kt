// NEW: V4-133 — the six new console routes, THROUGH A REAL ControlServer over HTTP with the mgmt
// key, so a route registered outside the guard or under the wrong path fails here rather than in
// production (the same rig CompactionInstructionsRouteTest/TopologyRoutesTest use). One shared
// server for all four features: budgets, alerts (+ test send), capture, and playground.
package splice.control.api.usage

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.CompactView
import splice.control.ControlServer
import splice.control.HeadCompactSource
import splice.control.HeadLogSource
import splice.control.ManagedHead
import splice.control.api.turns.PlaygroundFailure
import splice.control.api.turns.PlaygroundProbe
import splice.control.api.turns.PlaygroundResult
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyParse
import splice.core.topology.TopologyWriter
import splice.usage.alerts.AlertStore
import splice.usage.budgets.BudgetStore
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import io.ktor.server.routing.post as serverPost

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HEAD_KEY = "claude"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleV4133RoutesTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("v4133-routes").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.start()
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    // ---- budgets ---------------------------------------------------------------------------

    @Test
    fun `an unwired budget store answers a named 503, never an empty list`() = runBlocking {
        awaitPort()
        control.ports.budgets = null
        val response = req { get("$url/api/budgets") { auth() } }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("no budget store"), response.bodyAsText())
    }

    @Test
    fun `a PUT with no action fills the Knob default, and GET reports what was saved`() = runBlocking {
        awaitPort()
        control.ports.budgets = BudgetStore(Files.createTempDirectory("budgets1").resolve("budgets.json"))
        val put = req {
            put("$url/api/budgets") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"budgets":[{"head":"claude","daily_usd":5.0}]}""")
            }
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val saved = json.parseToJsonElement(put.bodyAsText()).jsonObject["budgets"]!!.jsonArray.single().jsonObject
        assertEquals("warn", saved["action"]!!.jsonPrimitive.content, "the Knob-backed default fills a bare row")

        val get = req { get("$url/api/budgets") { auth() } }
        assertEquals(put.bodyAsText(), get.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, req { get("$url/api/budgets") }.status, "the read is guarded")
    }

    @Test
    fun `a refused budget write is a 400 naming why, and unauthorized is 401`() = runBlocking {
        awaitPort()
        control.ports.budgets = BudgetStore(Files.createTempDirectory("budgets2").resolve("budgets.json"))
        val bad = req {
            put("$url/api/budgets") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"budgets":[{"head":"claude","action":"yell"}]}""")
            }
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("must be one of"), bad.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, req { put("$url/api/budgets") { setBody("{}") } }.status)
    }

    // ---- alerts + test send ------------------------------------------------------------------

    @Test
    fun `an unwired alert store answers a named 503`() = runBlocking {
        awaitPort()
        control.ports.alerts = null
        val response = req { get("$url/api/alerts") { auth() } }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("no alert store"), response.bodyAsText())
    }

    @Test
    fun `alerts round-trip, and a bad webhook_url is refused with the reason`() = runBlocking {
        awaitPort()
        control.ports.alerts = AlertStore(Files.createTempDirectory("alerts1").resolve("alerts.json"))
        val put = req {
            put("$url/api/alerts") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"desktop":true,"webhook_url":"https://hooks.example/x"}""")
            }
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val get = req { get("$url/api/alerts") { auth() } }
        assertEquals(put.bodyAsText(), get.bodyAsText())

        val bad = req {
            put("$url/api/alerts") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"desktop":false,"webhook_url":"ftp://nope"}""")
            }
        }
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        assertTrue(bad.bodyAsText().contains("http(s)"), bad.bodyAsText())
    }

    @Test
    fun `test send with no saved webhook is a 409, and a real send reaches a real socket`() = runBlocking {
        awaitPort()
        val store = AlertStore(Files.createTempDirectory("alerts2").resolve("alerts.json"))
        control.ports.alerts = store

        val noHook = req { post("$url/api/alerts/test") { auth() } }
        assertEquals(HttpStatusCode.Conflict, noHook.status)
        assertTrue(noHook.bodyAsText().contains("PUT /api/alerts first"), noHook.bodyAsText())

        val hookPort = ServerSocket(0).use { it.localPort }
        var received: String? = null
        val hookServer = embeddedServer(Netty, port = hookPort, host = "127.0.0.1") {
            routing {
                serverPost("/hook") {
                    received = call.receiveText()
                    call.respondText("ok")
                }
            }
        }
        hookServer.start(wait = false)
        try {
            withTimeout(TIMEOUT_MS) {
                while (runCatching { ServerSocket(hookPort).close() }.isSuccess) delay(POLL_MS)
            }
            store.replace(store.settings().copy(webhookUrl = "http://127.0.0.1:$hookPort/hook"))
            val sent = req { post("$url/api/alerts/test") { auth() } }
            assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
            assertTrue(json.parseToJsonElement(sent.bodyAsText()).jsonObject["ok"]!!.jsonPrimitive.boolean)
            val gotIt = received?.contains("splice test alert") == true
            assertTrue(gotIt, "the fixture endpoint received the ping: $received")
        } finally {
            hookServer.stop(50, 200)
        }
    }

    // ---- capture ------------------------------------------------------------------------------

    @Test
    fun `capture GET reports the effective trace defaults for a known head, and 400s an unknown one`() =
        runBlocking {
            awaitPort()
            val known = req { get("$url/api/heads/$HEAD_KEY/capture") { auth() } }
            assertEquals(HttpStatusCode.OK, known.status, known.bodyAsText())
            val body = json.parseToJsonElement(known.bodyAsText()).jsonObject
            assertFalse(body["enabled"]!!.jsonPrimitive.boolean, "trace is off by default")
            assertTrue(body["restart_required"]!!.jsonPrimitive.boolean, "the switch is restart-required today")

            val unknown = req { get("$url/api/heads/no-such-head/capture") { auth() } }
            assertEquals(HttpStatusCode.BadRequest, unknown.status, "unknown head is 400, never 404")
            assertTrue(unknown.bodyAsText().contains("no-such-head"), unknown.bodyAsText())
        }

    @Test
    fun `capture PUT is a named 503 when no topology writer is wired`() = runBlocking {
        awaitPort()
        control.ports.topology = null
        val response = req {
            put("$url/api/heads/$HEAD_KEY/capture") {
                auth()
                contentType(ContentType.Application.Json)
                setBody("""{"enabled":true}""")
            }
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("topology writer"), response.bodyAsText())
    }

    // The trace knob is already ON in both the starting text and topology the fake parser maps it
    // to, so the requested edit is a no-op at the canonical-tree level: TomlPatch composes back to
    // the SAME bytes, taking write()'s "nothing moved" fast path (TopologyWriteResult.Written(null))
    // instead of needing this test to predict TomlPatch's own ADDED-key text — that algorithm is
    // TopologyWriterTest's and TopologyWriterRoundTripTest's job, not CaptureRoutes'. This still
    // proves the WHOLE wired path — decode current, build the overrides map, call the real writer —
    // reaches a 200, which is what CaptureRoutes itself is answerable for.
    @Test
    fun `a wired capture PUT decodes the topology, builds the overrides map, and lands a real write`() =
        runBlocking {
            awaitPort()
            val captureFile = "[heads.$HEAD_KEY]\nport = 9001\noverrides = { trace = \"true\" }\n"
            val captureTopology = Topology(
                providers = mapOf(
                    "p" to ProviderConfig(
                        Dialect.ANTHROPIC_PASSTHROUGH,
                        "https://api.example.com",
                        AuthConfig("api-key", env = "P_KEY"),
                        models = listOf(ModelEntry("m1", contextWindow = 128_000L)),
                    ),
                ),
                heads = mapOf(
                    HEAD_KEY to HeadConfig(
                        "p",
                        9001,
                        "$HEAD_KEY/",
                        "m1",
                        overrides = mapOf("trace" to "true"),
                    ),
                ),
            )
            val tmp = Files.createTempDirectory("capture-write")
            val file = tmp.resolve("splice.toml").also { Files.writeString(it, captureFile) }
            control.ports.topology = TopologyWriter(
                file,
                TopologyParse { text ->
                    require(text == captureFile) { "unexpected: $text" }
                    captureTopology
                },
            )

            val response = req {
                put("$url/api/heads/$HEAD_KEY/capture") {
                    auth()
                    contentType(ContentType.Application.Json)
                    setBody("""{"enabled":true}""")
                }
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertTrue(json.parseToJsonElement(response.bodyAsText()).jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals(captureFile, Files.readString(file), "an idempotent edit leaves the file byte-identical")
        }

    // ---- playground ----------------------------------------------------------------------------

    @Test
    fun `playground is a named 503 when no probe is wired`() = runBlocking {
        awaitPort()
        control.ports.playground = null
        val unwired = req { post("$url/api/playground") { playgroundBody(HEAD_KEY, "hi") } }
        assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status)
    }

    @Test
    fun `playground is 400 on an unknown head, and on a blank prompt before the probe ever runs`() = runBlocking {
        awaitPort()
        control.ports.playground = echoProbe()
        val unknownHead = req { post("$url/api/playground") { playgroundBody("no-such-head", "hi") } }
        assertEquals(HttpStatusCode.BadRequest, unknownHead.status)
        val blank = req { post("$url/api/playground") { playgroundBody(HEAD_KEY, "") } }
        assertEquals(HttpStatusCode.BadRequest, blank.status)
    }

    @Test
    fun `playground maps a successful probe result to 200, and a probe failure to 502`() = runBlocking {
        awaitPort()
        control.ports.playground = echoProbe()
        val ok = req { post("$url/api/playground") { playgroundBody(HEAD_KEY, "hello") } }
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val okBody = json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertEquals("hello", okBody["request"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        assertEquals("echo: hello", okBody["response"]!!.jsonObject["text"]!!.jsonPrimitive.content)

        val failed = req { post("$url/api/playground") { playgroundBody(HEAD_KEY, "fail") } }
        assertEquals(HttpStatusCode.BadGateway, failed.status)
        assertTrue(failed.bodyAsText().contains("upstream rejected it"), failed.bodyAsText())
    }

    // ---- shared rig ----------------------------------------------------------------------------

    /** A fake probe: "fail" answers a [PlaygroundFailure], anything else echoes the prompt back. */
    private fun echoProbe() = PlaygroundProbe { _, prompt ->
        if (prompt == "fail") {
            PlaygroundFailure("upstream rejected it")
        } else {
            val request = buildJsonObject { put("prompt", prompt) }
            val response = buildJsonObject { put("text", "echo: $prompt") }
            PlaygroundResult(request, response)
        }
    }

    private fun HttpRequestBuilder.playgroundBody(head: String, prompt: String) {
        auth()
        contentType(ContentType.Application.Json)
        setBody("""{"head":"$head","prompt":"$prompt"}""")
    }

    private suspend fun req(request: suspend HttpClient.() -> HttpResponse): HttpResponse =
        withTimeout(TIMEOUT_MS) { request(client) }

    private fun HttpRequestBuilder.auth() {
        header("Authorization", "Bearer $key")
    }

    private fun managedHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = HEAD_KEY
            override val label: String = key
            override val port: Int = 0
            override suspend fun start() = Unit
            override suspend fun stop() = Unit
            override fun healthSnapshot(): HeadHealth = HeadHealth(true, true, port, "test")
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
            if (runCatching { ServerSocket(port).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :$port")
    }
}
