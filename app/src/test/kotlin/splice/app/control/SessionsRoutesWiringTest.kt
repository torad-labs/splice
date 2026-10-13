// NEW: V4-130 — the session routes answer THROUGH A REAL ControlServer, over HTTP with the mgmt key: the
// transcript route is routed, and the sessions listing serves its rows with or without the activity stores.
package splice.app.control

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.WallClock
import splice.sessions.activity.ActivityStores
import splice.sessions.registry.SessionRegistry
import splice.sessions.registry.SessionRoute
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

private const val SESSIONS_AT = 1_789_725_600_000L
private const val ONE = "d4d4d4d4-0000-4000-8000-000000000004"
private const val TWO = "e5e5e5e5-0000-4000-8000-000000000005"
private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L
private const val CLIENT_VERSION = "2.1.289"

class SessionsRoutesWiringTest {

    @TempDir
    lateinit var tmp: Path

    private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** A real control plane over a two-session registry, [stores] assigned the way ControlPlane does.
     *  [test] gets a reader with the mgmt key and one without it. */
    private fun serve(
        stores: ActivityStores?,
        test: suspend (get: suspend (String) -> HttpResponse, bare: suspend (String) -> HttpResponse) -> Unit,
    ) {
        val sessions = Files.createDirectories(tmp.resolve("sessions"))
        for ((pid, id) in listOf(1 to ONE, 2 to TWO)) {
            val row = """{"pid":$pid,"sessionId":"$id","updatedAt":$SESSIONS_AT,""" +
                """"messagingSocketPath":"/run/$pid.sock","version":"$CLIENT_VERSION"}"""
            Files.writeString(sessions.resolve("$pid.json"), row)
        }
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val control = controlServerFor(
            port = 0,
            // bound by the OS at start and read back below: no lease-then-bind window
            heads = emptyMap(),
            config = ConfigService(paths),
            runtime = ControlRuntime(
                sessions = SessionRegistry(
                    sessionsDir = sessions,
                    routeOf = { SessionRoute.Unknown },
                    pidAlive = { true },
                    clock = { SESSIONS_AT },
                ),
            ),
            auth = ControlAuth(mgmtKey = mgmt, log = { }),
        )
        control.ports.activity = stores
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    val url = { path: String -> "http://127.0.0.1:$port$path" }
                    test(
                        { path -> client.get(url(path)) { header("Authorization", "Bearer ${mgmt.get()}") } },
                        { path -> client.get(url(path)) },
                    )
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    @Test
    fun `the transcript route is routed and answers over the wire`() {
        val stores = ActivityStores(tmp.resolve("activity"), 90, "*", WallClock { SESSIONS_AT })
        serve(stores) { get, _ ->
            // The transcript route is reached. A bad cursor is refused before any tree is opened, so
            // this proves the routing without reading the test JVM's own ~/.claude.
            val refused = get("/api/sessions/$ONE/transcript?cursor=bogus&limit=5")
            assertEquals(400, refused.status.value, refused.bodyAsText())
            assertEquals(json("""{"error":"not a cursor this daemon minted"}"""), json(refused.bodyAsText()))
        }
    }

    @Test
    fun `GET api sessions lists each registered session with its head, availability and client version`() {
        serve(null) { get, bare ->
            assertEquals(401, bare("/api/sessions").status.value, "the listing is read with the mgmt key only")

            val reply = get("/api/sessions")

            assertEquals(200, reply.status.value, reply.bodyAsText())
            val body = json(reply.bodyAsText())
            assertTrue(body.getValue("note").jsonPrimitive.content.contains("headless `claude -p`"), body.toString())
            val rows = body.getValue("sessions").jsonArray.map { it.jsonObject }.associateBy {
                it.getValue("session_id").jsonPrimitive.content
            }
            assertEquals(setOf(ONE, TWO), rows.keys)
            rows.values.forEach { row ->
                assertEquals(CLIENT_VERSION, row.getValue("version").jsonPrimitive.content, row.toString())
                assertEquals("live", row.getValue("availability").jsonPrimitive.content, row.toString())
                assertEquals("unknown head", row.getValue("head").jsonPrimitive.content, row.toString())
            }
        }
    }

    @Test
    fun `without stores the sessions rows still serve, with no edges summary`() {
        serve(null) { get, _ ->
            val rows = get("/api/sessions")
            assertEquals(200, rows.status.value)
            val body = rows.bodyAsText()
            assertTrue(!body.contains("\"edges\""), "no edges summary without stores: $body")
        }
    }
}
