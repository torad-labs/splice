// NEW: V4-137 — POST /api/daemon/restart through a real ControlServer under the bearer.
//
// THE UNSUPERVISED ARM IS THE ROW, and the assertion that matters is not the status code: it is that a
// REFUSED restart takes NO DRAIN. A route that refused with 409 and drained anyway would be the worst
// of both — the console reads a refusal while the daemon stops, so the operator sees an error and a
// dead daemon. So the rig counts shutdown requests and every refusal arm asserts the count is still
// zero, which is the same expected-delta instrument the campaign uses elsewhere: a number that should
// NOT move, checked.
//
// The supervised arm asserts the opposite and with the same counter: the drain WAS requested, and the
// payload says the phase the daemon is entering rather than one it has reached.
package splice.control.api.fleet

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.ControlServer
import splice.control.HeadLogSource
import splice.control.ManagedHead
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val HEAD_KEY = "claude"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DrainingRestartTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var control: ControlServer
    private lateinit var key: String

    /** The expected-delta instrument: a drain that should NOT happen is proven by this staying at 0. */
    private val drains = AtomicInteger()

    /** Flipped per test; the rig is supervised or not without pretending to be systemd. */
    private var supervised = true

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("draining-restart").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = mapOf(HEAD_KEY to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            shutdownDaemon = { drains.incrementAndGet() },
        )
        control.ports.supervised = DaemonSupervised { supervised }
        control.start()
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `the route needs the bearer`() = runBlocking {
        awaitPort()
        // RESET FIRST. The counter is shared across the class and the supervised arm increments it, so
        // reading it without resetting makes this assertion depend on test ORDER — it passed only while
        // JUnit happened to run this one first, and a mutation that drained in the unsupervised arm
        // turned it red for a reason that had nothing to do with the bearer.
        drains.set(0)
        val refused = withTimeout(TIMEOUT_MS) { client.post("$url/api/daemon/restart") }
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals(0, drains.get(), "an unauthenticated request must not drain the daemon")
    }

    @Test
    fun `a supervised daemon takes the drain and answers 202 draining`() = runBlocking {
        awaitPort()
        supervised = true
        drains.set(0)
        val response = post()
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        // The phase it is ENTERING, never a completion: the drain outlives this response.
        assertEquals("draining", body["status"]!!.jsonPrimitive.content)
        assertEquals(1, drains.get(), "the drain was actually requested, not merely reported")
    }

    @Test
    fun `an unsupervised daemon is REFUSED and nothing is drained`() = runBlocking {
        awaitPort()
        supervised = false
        drains.set(0)
        val response = post()
        assertEquals(
            HttpStatusCode.Conflict,
            response.status,
            "a drain here would leave the daemon down for good, so the request is refused: ${response.bodyAsText()}",
        )
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(
            body["error"]!!.jsonPrimitive.content.contains("was not started by systemd"),
            "the refusal must say WHY: ${response.bodyAsText()}",
        )
        // THE ASSERTION THIS TEST EXISTS FOR. A 409 that drained anyway reads as a refusal while the
        // daemon stops — the operator sees an error and a dead daemon.
        assertEquals(0, drains.get(), "a refused restart must take NO drain at all")
        assertFalse(body.containsKey("status"), "and must not report a draining phase: ${response.bodyAsText()}")
    }

    @Test
    fun `an unwired supervision probe is refused by NAME, differently from unsupervised`() = runBlocking {
        awaitPort()
        drains.set(0)
        control.ports.supervised = null
        val response = post()
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status, response.bodyAsText())
        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(
            body["error"]!!.jsonPrimitive.content.contains("did not wire a supervision probe"),
            "the failure must NAME the missing wire: ${response.bodyAsText()}",
        )
        assertEquals(0, drains.get(), "an unwired probe must take no drain either")
        control.ports.supervised = DaemonSupervised { supervised }
    }

    private suspend fun post(): HttpResponse = withTimeout(TIMEOUT_MS) {
        client.post("$url/api/daemon/restart") { header("Authorization", "Bearer $key") }
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
