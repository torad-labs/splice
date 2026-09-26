// NEW: V4-319 — a head's live turns and the operator's stop answer THROUGH A REAL ControlServer, over
// HTTP with the mgmt key: GET /api/heads/{head}/turns/live and POST /api/heads/{head}/turns/{id}/stop are
// routed (a missing routing line answers 404 here, by name), guarded (no key is a 401), and read the
// registry the daemon wires into ConsolePorts after construction, at call time. HeadServerTurnStopTest
// proves the stop itself on a real head with a held upstream turn.
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.auth.AuthDescription
import splice.core.auth.AuthProvider
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.head.Head
import splice.core.head.HeadHealth
import splice.diagnostics.logs.HeadLogSource
import splice.head.compact.CompactView
import splice.head.compact.HeadCompactSource
import splice.head.turn.LiveTurns
import splice.head.turn.LiveTurnsByHead
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.RateLimitView
import splice.usage.quota.UsageView
import java.net.Socket
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

class TurnStopWiringTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String) = Json.parseToJsonElement(body).jsonObject

    private data class Calls(
        val get: suspend (String) -> HttpResponse,
        val post: suspend (String) -> HttpResponse,
        val bare: suspend (String) -> HttpResponse,
    )

    @Test
    fun `the live-turn routes are served and guarded, and say when no registry is wired - V4-319`() {
        serve(ports = { }) { calls ->
            val unwired =
                """{"error":"the daemon wired no live-turn registry; a head's turns cannot be read or stopped"}"""
            val live = calls.get("/api/heads/codex/turns/live")
            assertEquals(503, live.status.value, live.bodyAsText())
            assertEquals(json(unwired), json(live.bodyAsText()))
            val stop = calls.post("/api/heads/codex/turns/some-turn/stop")
            assertEquals(503, stop.status.value, stop.bodyAsText())
            assertEquals(json(unwired), json(stop.bodyAsText()))
            assertEquals(401, calls.bare("/api/heads/codex/turns/live").status.value)
        }
    }

    @Test
    fun `the routes read the head's own registry, name an unknown head, and 404 a turn that is not live`() {
        serve(ports = { it.liveTurns = LiveTurnsByHead().apply { put("codex", LiveTurns()) } }) { calls ->
            val live = calls.get("/api/heads/codex/turns/live")
            assertEquals(200, live.status.value, live.bodyAsText())
            assertEquals(json("""{"head":"codex","turns":[]}"""), json(live.bodyAsText()))
            val ended = calls.post("/api/heads/codex/turns/ended-turn/stop")
            assertEquals(404, ended.status.value, ended.bodyAsText())
            val gone = """{"error":"no live turn ended-turn on head codex: it has ended"}"""
            assertEquals(json(gone), json(ended.bodyAsText()))
            val unknown = calls.get("/api/heads/nope/turns/live")
            assertEquals(400, unknown.status.value, unknown.bodyAsText())
            assertEquals(json("""{"error":"unknown head: nope"}"""), json(unknown.bodyAsText()))
        }
    }

    /** What a test sets on the server's ports before it serves. */
    private fun interface PortsSetup {
        fun set(ports: ConsolePorts)
    }

    /** A real control plane with one head keyed codex, its ports set by [ports]. */
    private fun serve(ports: PortsSetup, test: suspend (Calls) -> Unit) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val control = ControlServer(
            port = 0,
            heads = mapOf("codex" to managedHead()),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
        )
        ports.set(control.ports)
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    val url = { path: String -> "http://127.0.0.1:$port$path" }
                    val key = "Bearer ${mgmt.get()}"
                    test(
                        Calls(
                            get = { path -> client.get(url(path)) { header("Authorization", key) } },
                            post = { path -> client.post(url(path)) { header("Authorization", key) } },
                            bare = { path -> client.get(url(path)) },
                        ),
                    )
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    private fun managedHead(): ManagedHead = ManagedHead(
        head = object : Head {
            override val key: String = "codex"
            override val label: String = "claudex"
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
}
