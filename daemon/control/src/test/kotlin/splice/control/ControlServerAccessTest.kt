// NEW: v0.4.0 — WHO may open WHICH control route, proven over real HTTP against a real ControlServer:
// the turn key a launched session holds opens that session's own hook routes and nothing else, and a
// request naming a non-loopback Host (a DNS-rebinding page) is refused before any route runs. Split
// from ControlServerTest, which covers what the routes ANSWER; this file covers who gets in.
package splice.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlServerAccessTest {

    private lateinit var control: ControlServer
    private lateinit var mgmtKey: String
    private lateinit var turnKey: String
    private val dashboardRenders = AtomicInteger()
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val port: Int get() = control.listeningPort

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("control-access").resolve("state"))
        val mgmt = MgmtKey(paths, log = {})
        mgmtKey = mgmt.get()
        turnKey = TurnKey(mgmt).get()
        // No heads: every guard runs BEFORE head resolution, so the door is exercised whatever
        // the route would answer, and the session routes answer 200 for an unknown head.
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = {
                dashboardRenders.incrementAndGet()
                "<!doctype html><title>splice</title>"
            },
            log = {},
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    // A launched session holds the TURN key in its environment, where every tool the model runs can
    // read it. It opens the routes that session's own statusline command and SessionStart hook call,
    // and nothing of the management plane: no status, no config, no logs, no launch.
    @Test
    fun `the turn key opens a session's own hook routes and nothing else`() = runBlocking {
        val statusline = client.post("http://127.0.0.1:$port/statusline/codex") {
            header("Authorization", "Bearer $turnKey")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, statusline.status, "POST /statusline")
        val statuslineGet = client.get("http://127.0.0.1:$port/statusline/codex") {
            header("Authorization", "Bearer $turnKey")
        }
        assertEquals(HttpStatusCode.OK, statuslineGet.status, "GET /statusline")
        val resume = client.post("http://127.0.0.1:$port/hooks/resume/codex") {
            header("Authorization", "Bearer $turnKey")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, resume.status, "POST /hooks/resume")
        for (path in listOf("/api/status", "/api/heads", "/api/config", "/api/logs/codex", "/api/usage")) {
            val response = client.get("http://127.0.0.1:$port$path") { header("Authorization", "Bearer $turnKey") }
            assertEquals(HttpStatusCode.Unauthorized, response.status, path)
        }
        val launch = client.post("http://127.0.0.1:$port/launch/codex") {
            header("Authorization", "Bearer $turnKey")
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, launch.status, "POST /launch")
        val status = client.get("http://127.0.0.1:$port/api/status") { header("Authorization", "Bearer $mgmtKey") }
        assertEquals(HttpStatusCode.OK, status.status, "the management key still opens the plane")
    }

    // DNS rebinding. A page in the operator's browser that rebinds attacker.example to 127.0.0.1
    // reads loopback responses as its own origin — /health and the dashboard HTML need no key. Its
    // requests name attacker.example in Host, so the listener refuses them before routing: the
    // dashboard handler never runs, and even a request carrying the key is refused.
    @Test
    fun `a request naming a foreign Host is refused before any route runs`() {
        val rendersBefore = dashboardRenders.get()
        assertEquals(403, rawStatus("/", "attacker.example:$port"))
        assertEquals(rendersBefore, dashboardRenders.get(), "the dashboard handler never ran")
        assertEquals(403, rawStatus("/health", "attacker.example:$port"))
        assertEquals(403, rawStatus("/api/status", "attacker.example:$port", bearer = mgmtKey), "even with the key")
        assertEquals(200, rawStatus("/health", "localhost:$port"))
        assertEquals(200, rawStatus("/api/status", "[::1]:$port", bearer = mgmtKey))
    }

    /** Raw HTTP/1.1, so the Host line is exactly the one written here — a client library sets its
     *  own from the URL, and this arm is about what a REBINDING page's browser sends. */
    private fun rawStatus(path: String, host: String, bearer: String? = null): Int {
        val request = buildString {
            append("GET $path HTTP/1.1\r\nHost: $host\r\n")
            bearer?.let { append("Authorization: Bearer $it\r\n") }
            append("Connection: close\r\n\r\n")
        }
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }
    }
}
