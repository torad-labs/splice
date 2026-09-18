// NEW: V4-126 — the wired route test: /api/events through a REAL ControlServer, under the bearer.
//
// The shape tests prove the bus is right and the frame format is right; neither proves the route is
// REACHABLE. This drives an actual server on a real port, because the failure this guards against —
// a route registered outside the guard, or answered under a path the console does not call — is
// invisible to a test that constructs EventsRoute directly. The rig mirrors McpRoutesTest, the
// module's existing proof that a streaming route is wired and guarded.
//
// No Thread.sleep anywhere: the retry uses a coroutine delay, so this file adds nothing to the
// kt-tests-no-wall-clock allowlist that V4-111 exists to empty.
package console

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.control.ControlServer
import splice.control.api.ConsoleEvent
import splice.control.api.EventBus
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.net.ServerSocket
import java.nio.file.Files

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 25L
private const val OPEN_FRAME = ": open"

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EventsRouteTest {

    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private val client = HttpClient(CIO) { expectSuccess = false }
    private lateinit var control: ControlServer
    private lateinit var key: String

    /** V4-134: the bus is ASSIGNED, the way ControlPlane assigns the daemon's one bus — the server no
     *  longer builds its own, so the test hands it this one and publishes to it. */
    private val bus = EventBus()

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("events-route").resolve("state"))
        val mgmt = MgmtKey(paths)
        key = mgmt.get()
        control = ControlServer(
            port = port,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        control.events = bus
        control.start()
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    @Test
    fun `the stream needs the bearer, exactly like every other control route`() = runBlocking {
        awaitPort()
        val refused = withTimeout(TIMEOUT_MS) { client.get("$url/api/events") }
        assertEquals(HttpStatusCode.Unauthorized, refused.status, "an unguarded stream is a leak")
    }

    @Test
    fun `a server with no bus assigned answers a named 503, never an empty stream`() = runBlocking {
        awaitPort()
        control.events = null
        try {
            val unwired = withTimeout(TIMEOUT_MS) {
                client.get("$url/api/events") { header("Authorization", "Bearer $key") }
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, unwired.status, "an unwired bus must not stream")
            val body = unwired.bodyAsText()
            assertTrue("wired no console event bus" in body, "the 503 must name what was not wired: $body")
        } finally {
            control.events = bus
        }
    }

    @Test
    fun `a published event reaches the wire as id, event and one json data object`() = runBlocking {
        awaitPort()
        withTimeout(TIMEOUT_MS) {
            client.prepareGet("$url/api/events") { header("Authorization", "Bearer $key") }.execute { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                val channel = response.bodyAsChannel()
                assertEquals(OPEN_FRAME, channel.readUTF8Line(), "the stream opens with its comment frame")

                val published = bus.publish { seq -> ConsoleEvent.HeadState(seq, "claude", "running") }
                val frame = readFrame(channel)
                assertEquals("id: ${published.seq}", frame[0])
                assertEquals("event: head.state", frame[1])
                val data = frame[2].removePrefix("data: ")
                assertTrue(data.startsWith("{") && data.endsWith("}"), "data must be one JSON object: $data")
                assertTrue(data.contains("\"head\":\"claude\""), data)
                // The kind is the event: field, so it must NOT also ride inside data as a discriminator.
                assertTrue(!data.contains("\"type\""), "no type discriminator in the payload: $data")
            }
        }
    }

    @Test
    fun `Last-Event-ID resumes after the given id and replays nothing before it`() = runBlocking {
        awaitPort()
        val first = bus.publish { seq -> ConsoleEvent.TurnStart(seq, "claude", "s-1") }
        val second = bus.publish { seq -> ConsoleEvent.TurnStart(seq, "claude", "s-2") }
        withTimeout(TIMEOUT_MS) {
            client.prepareGet("$url/api/events") {
                header("Authorization", "Bearer $key")
                header("Last-Event-ID", first.seq.toString())
            }.execute { response ->
                val channel = response.bodyAsChannel()
                channel.readUTF8Line() // the open comment
                val frame = readFrame(channel)
                assertEquals("id: ${second.seq}", frame[0], "resume must start strictly after the given id")
            }
        }
    }

    /** One SSE frame's field lines, skipping any heartbeat comment that arrives first. */
    private suspend fun readFrame(channel: ByteReadChannel): List<String> {
        val lines = mutableListOf<String>()
        while (lines.size < 3) {
            val line = channel.readUTF8Line() ?: error("the stream ended mid-frame: $lines")
            if (line.startsWith(":") || line.isEmpty()) continue
            lines += line
        }
        return lines
    }

    /** The engine binds asynchronously, so the first request is retried until it connects. */
    private suspend fun awaitPort() {
        val deadline = System.nanoTime() + TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            val bound = runCatching { ServerSocket(port).close() }.isFailure
            if (bound) return
            delay(POLL_MS)
        }
        error("the control server never bound :$port")
    }
}
