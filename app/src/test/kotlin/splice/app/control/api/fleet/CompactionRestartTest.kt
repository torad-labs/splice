// NEW: V4-220 item 6 — the console's restart waits for a compaction in flight, as `splice restart`
// does since V4-216. POST /api/daemon/restart went straight into the 45 s drain, so the console's
// button cut the very compaction whose answer the client's retry was about to need. Driven through a
// real ControlServer under the bearer, over a head whose gate reports what V4-213's live rows carry;
// the drain counter is the instrument, as in DrainingRestartTest.
package splice.app.control.api.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.app.control.ControlServer
import splice.app.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.GateHealth
import splice.core.head.GatePhase
import splice.core.head.GateSlot
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.lifecycle.restart.DaemonSupervised
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L

// Long enough for a drain requested right after the 202 to land, which is how the route ran before.
private const val SETTLE_MS = 1_500L
private const val HEAD_KEY = "claudex"
private const val TWO_MINUTES_TEN_MS = 130_000L
private const val PAST_THE_CAP_MS = 600_000L

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompactionRestartTest {

    private val port: Int get() = control.listeningPort
    private val url: String get() = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String
    private val drains = AtomicInteger()

    /** What the head's gate reports as in flight right now; null is an idle gate. */
    @Volatile private var inFlight: GateSlot? = null

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("compaction-restart").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = 0,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            shutdownDaemon = { drains.incrementAndGet() },
        )
        control.ports.supervised = DaemonSupervised { true }
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @BeforeEach
    fun reset() {
        inFlight = null
        drains.set(0)
    }

    /** RED before V4-220: the route answered 202 and requested the drain at once. */
    @Test
    fun `a console restart with a compaction in flight drains only once the compaction is gone`() = runBlocking {
        awaitPort()
        inFlight = slot(compact = true, ageMs = TWO_MINUTES_TEN_MS)
        val response = post("/api/daemon/restart")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(0, drainsAfter(SETTLE_MS), "drained while a compaction was in flight: ${response.bodyAsText()}")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("waiting", body["status"]!!.jsonPrimitive.content, response.bodyAsText())
        val waitingOn = body["compactions"]!!.jsonArray.single().jsonObject
        assertEquals(HEAD_KEY, waitingOn["head"]!!.jsonPrimitive.content)
        assertEquals(TWO_MINUTES_TEN_MS, waitingOn["age_ms"]!!.jsonPrimitive.content.toLong())

        val status = json.parseToJsonElement(get("/api/daemon/restart").bodyAsText()).jsonObject
        assertEquals("waiting", status["status"]!!.jsonPrimitive.content, "the pending restart is visible")

        inFlight = null
        assertEquals(1, drainsAfter(TIMEOUT_MS), "the drain follows the compaction's end")
        val after = json.parseToJsonElement(get("/api/daemon/restart").bodyAsText()).jsonObject
        assertEquals("draining", after["status"]!!.jsonPrimitive.content, "and the status says so")
    }

    @Test
    fun `ordinary turns in flight never hold a console restart`() = runBlocking {
        awaitPort()
        inFlight = slot(compact = false, ageMs = TWO_MINUTES_TEN_MS)
        val response = post("/api/daemon/restart")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(1, drainsAfter(TIMEOUT_MS), "the stop's own drain is theirs, not this wait")
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("draining", body["status"]!!.jsonPrimitive.content, response.bodyAsText())
    }

    @Test
    fun `now drains at once with a compaction in flight`() = runBlocking {
        awaitPort()
        inFlight = slot(compact = true, ageMs = TWO_MINUTES_TEN_MS)
        val response = post("/api/daemon/restart?now=1")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        assertEquals(1, drainsAfter(TIMEOUT_MS), "now skips the wait")
    }

    @Test
    fun `now ends a wait already pending`() = runBlocking {
        awaitPort()
        inFlight = slot(compact = true, ageMs = TWO_MINUTES_TEN_MS)
        post("/api/daemon/restart")
        assertEquals(0, drainsAfter(SETTLE_MS), "waiting")
        post("/api/daemon/restart?now=1")
        assertEquals(1, drainsAfter(TIMEOUT_MS), "now drains the pending restart")
        inFlight = null
        assertEquals(1, drainsAfter(SETTLE_MS, from = 1), "and the cancelled wait never drains a second time")
    }

    @Test
    fun `a compaction past the client's 600 s is not waited for`() = runBlocking {
        awaitPort()
        inFlight = slot(compact = true, ageMs = PAST_THE_CAP_MS)
        post("/api/daemon/restart")
        assertEquals(1, drainsAfter(TIMEOUT_MS))
    }

    private fun slot(compact: Boolean, ageMs: Long) =
        GateSlot("b2e4d8f1 gpt-5.6-sol", compact, GatePhase.STREAMING, ageMs, 0)

    /** The drain count once [ms] has passed, returned early the moment it moves off [from]: a count
     *  that should stay put is read at the end of the window, one that should move as soon as it does. */
    private suspend fun drainsAfter(ms: Long, from: Int = 0): Int {
        val deadline = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < deadline && drains.get() == from) delay(POLL_MS)
        return drains.get()
    }

    private suspend fun post(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.post("$url$path") { header("Authorization", "Bearer $key") }
    }

    private suspend fun get(path: String): HttpResponse = withTimeout(TIMEOUT_MS) {
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
            if (runCatching { ServerSocket(port).close() }.isFailure) return
            delay(POLL_MS)
        }
        error("the control server never bound :$port")
    }
}
