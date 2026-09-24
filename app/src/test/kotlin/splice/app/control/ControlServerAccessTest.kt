// NEW: v0.4.0 — WHO may open WHICH control route, proven over real HTTP against a real ControlServer:
// the turn key a launched session holds opens that session's own hook routes and nothing else, and a
// request naming a non-loopback Host (a DNS-rebinding page) is refused before any route runs. Split
// from ControlServerTest, which covers what the routes ANSWER; this file covers who gets in.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import splice.client.mcp.DirectoryProbe
import splice.client.mcp.McpAccessKey
import splice.client.mcp.McpSharing
import splice.control.mcp.McpHost
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The rows a launched session's own statusline command and SessionStart hook call: the only ones its
 *  turn key opens (Door.SESSION at UsageMount and LaunchMount). */
private val SESSION_ROWS = setOf("GET /statusline/{head}", "POST /statusline/{head}", "POST /hooks/resume/{head}")

/** The rows that answer with no key at all: the liveness probe, and the dashboard page at both of its
 *  paths (FleetMount), which asks for the key itself before it calls anything. */
private val OPEN_ROWS = setOf("GET /health", "GET /", "GET /dashboard")

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ControlServerAccessTest {

    private lateinit var control: ControlServer
    private lateinit var mgmtKey: String
    private lateinit var turnKey: String
    private val dashboardRenders = AtomicInteger()
    private val logLines = CopyOnWriteArrayList<String>()
    private val client = HttpClient(CIO) { expectSuccess = false }
    private val port: Int get() = control.listeningPort

    @BeforeAll
    fun setUp() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("control-access").resolve("state"))
        val mgmt = MgmtKey(paths, log = {})
        mgmtKey = mgmt.get()
        turnKey = TurnKey(mgmt).get()
        // No heads: every guard runs BEFORE head resolution, so the door is exercised whatever
        // the route would answer, and the session routes answer 200 for an unknown head. An MCP host
        // with no servers, so the /mcp rows are in the table the walk reads: their door is Door.MCP.
        val sharing =
            McpSharing(true, emptySet(), "http://127.0.0.1:0/mcp/", McpAccessKey(mgmt::get), DirectoryProbe { false })
        control = ControlServer(
            port = 0,
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = {
                dashboardRenders.incrementAndGet()
                "<!doctype html><title>splice</title>"
            },
            log = { logLines += it },
            mcpHost = McpHost(sharing, { JsonObject(emptyMap()) }, log = { }),
        )
        runBlocking { control.start() }
    }

    @AfterAll
    fun tearDown() {
        control.stop()
        client.close()
    }

    // A launched session holds the TURN key in its environment, where every tool the model runs can
    // read it. It opens the routes that session's own statusline command and SessionStart hook call.
    // That it opens nothing ELSE is the walk below, over every route the router serves.
    @Test
    fun `the turn key opens a session's own hook routes`() = runBlocking {
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
        val status = client.get("http://127.0.0.1:$port/api/status") { header("Authorization", "Bearer $mgmtKey") }
        assertEquals(HttpStatusCode.OK, status.status, "the management key still opens the plane")
    }

    // v0.4.0 review: the list of routes the turn key must NOT open was five paths picked by hand, so a
    // route given Door.SESSION anywhere else, or registered with no guard at all, passed. The rows now
    // come from the router itself (ControlServer.routeTable), MCP routes included, and every one is
    // asked twice: with the turn key, and with no key. Only [SESSION_ROWS] may admit the first, only
    // [OPEN_ROWS] the second, and both lists must name rows the router actually serves.
    @Test
    fun `across every route the router serves, the turn key opens only the session rows`() {
        val rows = control.routeTable().map { routeRow(it.toString()) }.distinct()
        assertTrue(rows.size > OPEN_ROWS.size + SESSION_ROWS.size, "the router served no table: $rows")
        assertTrue(rows.containsAll(SESSION_ROWS + OPEN_ROWS), "an allowlisted row is not served: $rows")
        assertTrue(rows.any { it.contains("/mcp/") }, "the MCP rows are walked too: $rows")

        val wrong = rows.mapNotNull { row ->
            val (method, template) = row.split(" ", limit = 2)
            val path = template.replace(Regex("\\{[^}]*}"), "codex")
            val withTurnKey = rawStatus(path, "127.0.0.1:$port", bearer = turnKey, method = method)
            val withNoKey = rawStatus(path, "127.0.0.1:$port", method = method)
            val turnKeyAdmitted = row in SESSION_ROWS || row in OPEN_ROWS
            val noKeyAdmitted = row in OPEN_ROWS
            if ((withTurnKey != 401) == turnKeyAdmitted && (withNoKey != 401) == noKeyAdmitted) {
                null
            } else {
                "$row: turn key $withTurnKey, no key $withNoKey"
            }
        }
        assertEquals(emptyList<String>(), wrong)
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
        // v0.4.0 review: and the refusal is SAID, once for the name however many requests carried it.
        val said = logLines.filter { it.contains("attacker.example") }
        assertEquals(1, said.size, logLines.toString())
        assertTrue(said.single().startsWith("[security] the control plane refused"), said.single())
    }

    /** `/statusline/{key}/(method:POST)` — how the router renders a route — as `POST /statusline/{key}`. */
    private fun routeRow(rendered: String): String {
        val method = Regex("\\(method:(\\w+)\\)").find(rendered)?.groupValues?.get(1) ?: "ANY"
        val path = rendered.replace(Regex("/?\\(method:\\w+\\)"), "").ifEmpty { "/" }
        return "$method $path"
    }

    /** Raw HTTP/1.1, so the Host line is exactly the one written here — a client library sets its
     *  own from the URL, and this arm is about what a REBINDING page's browser sends. A method with a
     *  body carries `{}`, so a route that reads one is asked as a client would ask it. */
    private fun rawStatus(path: String, host: String, bearer: String? = null, method: String = "GET"): Int {
        val body = if (method == "GET" || method == "HEAD") "" else "{}"
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\n")
            bearer?.let { append("Authorization: Bearer $it\r\n") }
            if (body.isNotEmpty()) append("Content-Type: application/json\r\nContent-Length: ${body.length}\r\n")
            append("Connection: close\r\n\r\n")
            append(body)
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
