// NEW: V4-130 — the three session routes answer THROUGH A REAL ControlServer, over HTTP with the mgmt
// key. SessionsConsoleRoutesTest proves the payloads on SessionsRoutes directly; this file proves the
// lines in ControlServer that route to it and the `activity` property ControlPlane assigns, which no
// other test reaches: delete a routing line and its route answers 404 here, by name.
//
// UNWIRED IS PROVEN, NOT ASSERTED. With `activity` left null the two edges routes answer 503 with the
// named EDGES_UNWIRED body, never a 500 or a 200 with an empty list that a console would render as
// "this session has no edges" forever; the transcript route needs no stores and still answers.
package splice.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.activity.MessageEdge
import splice.sessions.registry.SessionRegistry
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val SESSIONS_AT = 1_789_725_600_000L
private const val ONE = "d4d4d4d4-0000-4000-8000-000000000004"
private const val TWO = "e5e5e5e5-0000-4000-8000-000000000005"
private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L
private const val UNWIRED = "the activity stores are not wired into this control plane"

class SessionsRoutesWiringTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** A real control plane over a two-session registry, [stores] assigned the way ControlPlane does. */
    private fun serve(stores: ActivityStores?, test: suspend (get: suspend (String) -> HttpResponse) -> Unit) {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        for ((pid, id) in listOf(1 to ONE, 2 to TWO)) {
            val row = """{"pid":$pid,"sessionId":"$id","updatedAt":$SESSIONS_AT,"messagingSocketPath":"/run/$pid.sock"}"""
            Files.writeString(sessions.resolve("$pid.json"), row)
        }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val control = ControlServer(
            port = 0, // bound by the OS at start and read back below: no lease-then-bind window
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            sessions = SessionRegistry(
                sessionsDir = sessions,
                headOf = { null },
                pidAlive = { true },
                clock = { SESSIONS_AT },
            ),
        )
        control.ports.activity = stores
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    test { path ->
                        client.get("http://127.0.0.1:$port$path") { header("Authorization", "Bearer ${mgmt.get()}") }
                    }
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    @Test
    fun `the three session routes are routed and answer their payloads over the wire`() {
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { SESSIONS_AT })
        stores.edges.record(MessageEdge(ONE, "uds:/run/2.sock", SESSIONS_AT, "toolu_1"))
        assertTrue(AsyncFileIo.drain(), "the file lane drained")
        serve(stores) { get ->
            val board = get("/api/sessions/edges")
            assertEquals(200, board.status.value, board.bodyAsText())
            val edge = """{"from":"$ONE","to":"uds:/run/2.sock","at":$SESSIONS_AT"""
            assertEquals(
                json("""{"sessions":{"$ONE":[$edge,"direction":"out"}],"$TWO":[$edge,"direction":"in"}]}}"""),
                json(board.bodyAsText()),
            )
            val two = get("/api/sessions/$TWO/edges")
            assertEquals(200, two.status.value, two.bodyAsText())
            assertEquals(json("""{"session_id":"$TWO","edges":[$edge,"direction":"in"}]}"""), json(two.bodyAsText()))
            // The transcript route is reached. A bad cursor is refused before any tree is opened, so
            // this proves the routing without reading the test JVM's own ~/.claude.
            val refused = get("/api/sessions/$ONE/transcript?cursor=bogus&limit=5")
            assertEquals(400, refused.status.value, refused.bodyAsText())
            assertEquals(json("""{"error":"not a cursor this daemon minted"}"""), json(refused.bodyAsText()))
        }
    }

    @Test
    fun `unwired stores answer both edges routes with the named 503, and the sessions rows still serve`() {
        serve(null) { get ->
            for (path in listOf("/api/sessions/edges", "/api/sessions/$ONE/edges")) {
                val reply = get(path)
                assertEquals(503, reply.status.value, "$path: ${reply.bodyAsText()}")
                assertEquals(json("""{"error":"$UNWIRED"}"""), json(reply.bodyAsText()), path)
            }
            val rows = get("/api/sessions")
            assertEquals(200, rows.status.value)
            val body = rows.bodyAsText()
            assertTrue(!body.contains("\"edges\""), "no edges summary without stores: $body")
        }
    }
}
