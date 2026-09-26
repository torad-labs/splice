// NEW: V4-239 — the three reads the console makes of what `splice models`, `splice trace` and
// `splice wire` print, THROUGH A REAL ControlServer over HTTP (the ConsoleV4133RoutesTest rig), so a
// route registered outside the guard, under the wrong path or on an unwired port fails here rather
// than in production. The first case is the one the row was ruled on: a call without the management
// key is refused on every one of them, with the ports wired, so the 401 cannot be a 503 in disguise.
package splice.app.control.api.turns

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.control.ControlServer
import splice.app.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.ProviderConfig
import splice.core.turn.ReasoningDisplay
import splice.core.turn.TurnMeta
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.wire.WireTap
import splice.head.wire.WireTaps
import splice.models.list.ModelConfiguration
import splice.models.list.ModelConfigurationSource
import splice.models.list.ModelCredentialSource
import splice.models.list.ModelsReporter
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HEAD_KEY = "claude"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsoleVerbReadsRoutesTest {

    private val port: Int get() = control.listeningPort
    private val url: String get() = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private lateinit var control: ControlServer
    private lateinit var key: String

    /** Every read this row added, as the console calls it. */
    private val reads: List<String> get() = listOf(
        "$url/api/models/upstream",
        "$url/api/heads/$HEAD_KEY/trace",
        "$url/api/heads/$HEAD_KEY/wire",
    )

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("v4239-routes").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `a call without the management key is refused on every read, wired or not`() = runBlocking {
        awaitPort()
        wireAll()
        reads.forEach { read ->
            assertEquals(HttpStatusCode.Unauthorized, req { get(read) }.status, "$read answered with no key")
            val wrong = req { get(read) { header("Authorization", "Bearer not-the-key") } }
            assertEquals(HttpStatusCode.Unauthorized, wrong.status, "$read answered a wrong key")
            assertFalse(wrong.bodyAsText().contains("records"), "$read leaked a payload: ${wrong.bodyAsText()}")
        }
    }

    @Test
    fun `with the key, each read serves what its verb prints`() = runBlocking {
        awaitPort()
        wireAll()

        val models = req { get("$url/api/models/upstream") { auth() } }
        assertEquals(HttpStatusCode.OK, models.status, models.bodyAsText())
        val provider = Json.parseToJsonElement(models.bodyAsText()).jsonObject.getValue("providers").jsonArray.single()
        assertEquals("mine", provider.jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("unpublished", provider.jsonObject.getValue("roster").jsonPrimitive.content)

        val trace = req { get("$url/api/heads/$HEAD_KEY/trace") { auth() } }
        assertEquals(HttpStatusCode.OK, trace.status, trace.bodyAsText())
        assertTrue(Json.parseToJsonElement(trace.bodyAsText()).jsonObject.getValue("turns").jsonArray.isEmpty())

        val wire = req { get("$url/api/heads/$HEAD_KEY/wire?last=1") { auth() } }
        assertEquals(HttpStatusCode.OK, wire.status, wire.bodyAsText())
        val records = Json.parseToJsonElement(wire.bodyAsText()).jsonObject.getValue("records").jsonArray
        assertEquals("""{"n":1}""", records.single().jsonObject.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun `a wrong provider, an unknown head and a tap that is off each answer in words, never 404`() = runBlocking {
        awaitPort()
        wireAll()

        val missing = req { get("$url/api/models/upstream?provider=nope") { auth() } }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(missing.bodyAsText().contains("no provider 'nope'") && missing.bodyAsText().contains("mine"))
        val unknown = req { get("$url/api/heads/nope/trace") { auth() } }
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
        control.ports.wires = WireTaps()
        val off = req { get("$url/api/heads/$HEAD_KEY/wire") { auth() } }
        assertEquals(HttpStatusCode.Conflict, off.status)
        assertTrue(off.bodyAsText().contains("wire tap is off for head $HEAD_KEY"), off.bodyAsText())
    }

    @Test
    fun `an unwired port answers a named 503, never an empty payload`() = runBlocking {
        awaitPort()
        control.ports.upstreamModels = null
        control.ports.traceDir = null
        control.ports.wires = null
        reads.forEach { read ->
            val response = req { get(read) { auth() } }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status, read)
            assertTrue(response.bodyAsText().contains("the daemon wired no"), response.bodyAsText())
        }
    }

    // ---- shared rig ----------------------------------------------------------------------------

    /** The three ports, as ConsoleWiring.wireVerbReads assigns them: a provider that needs no network
     *  (the client's own login publishes no list), an empty trace dir, and a tap holding two bodies. */
    private fun wireAll() {
        control.ports.upstreamModels = ModelsReporter(
            ModelConfigurationSource {
                ModelConfiguration(
                    "/fixture/splice.toml",
                    mapOf(
                        "mine" to ProviderConfig(
                            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                            baseUrl = "https://example.invalid",
                            auth = AuthConfig(kind = "client"),
                        ),
                    ),
                )
            },
            ModelCredentialSource { _, _, _ -> null },
        )
        control.ports.traceDir = Files.createTempDirectory("v4239-trace")
        val tap = WireTap(keep = 4)
        listOf("""{"n":0}""", """{"n":1}""").forEach { tap.record(meta(), it) }
        control.ports.wires = WireTaps().apply { put(HEAD_KEY, tap) }
    }

    private fun meta() = TurnMeta(
        compact = false,
        showReasoning = ReasoningDisplay.TEXT,
        stream = true,
        originalModel = "claude-sonnet",
        upstreamModel = "m1",
        clientMaxTokens = 8000,
        effort = "medium",
        summary = null,
        budgetTokens = null,
        sessionId = "s-1",
    )

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
