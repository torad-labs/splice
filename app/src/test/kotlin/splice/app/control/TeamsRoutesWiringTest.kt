// NEW: V4-131 — every team and project route answers THROUGH A REAL ControlServer over HTTP with the
// mgmt key. TeamsRoutesTest and ProjectsRoutesTest prove the payloads; this proves the routing lines
// and the `teams` property ControlPlane assigns: delete a routing line and its route answers 404 here,
// by name. It also pins what must NOT be routed: there is no GET /api/teams/{id} (FEATURES.md 6.1).
package splice.app.control

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.util.AsyncFileIo
import splice.sessions.activity.MessageEdge
import java.net.Socket
import java.net.URLEncoder
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L
private const val HOUR_MS = 3_600_000L

/** One request: method, path, body (null = none), and whether to send the mgmt key. */
private fun interface TeamsCall {
    suspend operator fun invoke(method: HttpMethod, path: String, body: String?, authorized: Boolean): HttpResponse
}

class TeamsRoutesWiringTest {

    @TempDir
    lateinit var tmp: Path

    private val rig by lazy { TeamRig(tmp) }

    private fun serve(wired: Boolean, test: suspend (TeamsCall) -> Unit) {
        val paths = StatePaths(baseOverride = tmp.resolve("state"))
        val mgmt = MgmtKey(paths)
        val control = ControlServer(
            port = 0, // bound by the OS at start and read back below: no lease-then-bind window
            heads = emptyMap(),
            config = ConfigService(paths),
            mgmtKey = mgmt,
            dashboardHtml = { "<!doctype html>" },
            log = { },
            sessions = rig.registry,
        )
        if (wired) {
            control.ports.teams = rig.store
            control.ports.activity = rig.stores
        }
        runBlocking { control.start() }
        val port = control.listeningPort
        val client = HttpClient(CIO) { expectSuccess = false }
        try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    while (runCatching { Socket("127.0.0.1", port).close() }.isFailure) delay(POLL_MS)
                    test { method, path, body, authorized ->
                        client.request("http://127.0.0.1:$port$path") {
                            this.method = method
                            if (authorized) header("Authorization", "Bearer ${mgmt.get()}")
                            header("Idempotency-Key", "wiring")
                            body?.let { setBody(it) }
                        }
                    }
                }
            }
        } finally {
            client.close()
            control.stop()
        }
    }

    @Test
    fun `every team and project route is routed, guarded, and answers its payload`() {
        val team = """{"name":"atlas","repo":"${rig.repo}","slots":[{"id":"b1","role":"builder","head":"codex"}]}"""
        val project = "/api/projects/" + URLEncoder.encode(rig.repo.toString(), Charsets.UTF_8)
        serve(wired = true) { call ->
            val created = call(HttpMethod.Put, "/api/teams", team, true)
            assertEquals(201, created.status.value, created.bodyAsText())
            val id = rig.json(created.bodyAsText()).getValue("id").jsonPrimitive.content
            val again = call(HttpMethod.Put, "/api/teams", team, true)
            assertEquals(200, again.status.value, "the Idempotency-Key header reaches the store: no second team")
            val routes = listOf(
                Triple(HttpMethod.Get, "/api/teams", null),
                Triple(HttpMethod.Put, "/api/teams/$id", team),
                Triple(HttpMethod.Put, "/api/teams/$id/sessions", """{"bindings":{"b1":"$BUILDER"}}"""),
                Triple(HttpMethod.Put, "/api/teams/$id/slots/b1/instructions", """{"instructions":"be terse"}"""),
                Triple(HttpMethod.Get, "/api/teams/$id/edges", null),
                Triple(HttpMethod.Get, "/api/teams/$id/chat?day=2026-09-18", null),
                Triple(HttpMethod.Get, "/api/teams/$id/activity?day=2026-09-18", null),
                Triple(HttpMethod.Get, "/api/teams/$id/economics", null),
                Triple(HttpMethod.Get, "/api/projects", null),
                Triple(HttpMethod.Get, project, null),
                Triple(HttpMethod.Get, "$project/files", null),
                Triple(HttpMethod.Post, "/api/teams/$id/archive", null),
            )
            for ((method, path, body) in routes) {
                val reply = call(method, path, body, true)
                assertEquals(200, reply.status.value, "${method.value} $path: ${reply.bodyAsText()}")
                assertEquals(401, call(method, path, body, false).status.value, "${method.value} $path is guarded")
            }
            val row = rig.json(call(HttpMethod.Get, project, null, true).bodyAsText())
            assertEquals(rig.repo.toString(), row.getValue("id").jsonPrimitive.content, "an encoded root decodes")
            val composite = call(HttpMethod.Get, "/api/teams/$id", null, true)
            assertEquals(404, composite.status.value, "no composite team read (6.1)")
        }
    }

    /** RED before V4-249: the day was a UTC date, so in Chicago the board turned over at 19:00 CDT and a
     *  read of the evening lost everything before UTC midnight. The console now sends its local day's
     *  bounds, and both halves come back. V4-285: the rows straddle UTC midnight with the stores' clock
     *  moved across it, as a live daemon's is, so each half lands in its own UTC day file. */
    @Test
    fun `a range read returns a local day that spans two UTC dates, both halves`() {
        val from = 1_789_621_200_000L // 2026-09-17T05:00Z: midnight of Sep 17 in Chicago (CDT)
        val to = from + 24 * HOUR_MS
        val beforeUtcMidnight = from + 18 * HOUR_MS // 18:00 CDT, 23:00Z on the 17th
        val afterUtcMidnight = from + 20 * HOUR_MS // 20:00 CDT, 01:00Z on the 18th
        val team = rig.team()
        rig.now = beforeUtcMidnight
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", beforeUtcMidnight, "toolu_a"))
        rig.stores.activity.label(LEAD, "claude", "Evening", beforeUtcMidnight)
        AsyncFileIo.drain()
        rig.now = afterUtcMidnight
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", afterUtcMidnight, "toolu_b"))
        rig.stores.edges.record(MessageEdge(LEAD, "uds:/run/2.sock", to, "toolu_c"))
        rig.stores.activity.label(LEAD, "claude", "Night", afterUtcMidnight)
        AsyncFileIo.drain()
        val both = listOf(beforeUtcMidnight, afterUtcMidnight).map(Long::toString)
        serve(wired = true) { call ->
            for ((panel, rows) in listOf("chat" to "messages", "activity" to "entries")) {
                val reply = call(HttpMethod.Get, "/api/teams/${team.id}/$panel?from=$from&to=$to", null, true)
                val body = rig.json(reply.bodyAsText())
                val ats = body.getValue(rows).jsonArray.map { it.jsonObject.getValue("at").jsonPrimitive.content }
                assertEquals(both, ats, "$panel: the local day's two halves, and nothing at its end")
                assertEquals(from.toString(), body.getValue("day_start_epoch_millis").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `the sessions rows carry the bound team, and an unwired store is a named 503`() {
        val team = rig.team()
        serve(wired = true) { call ->
            val rows = rig.json(call(HttpMethod.Get, "/api/sessions", null, true).bodyAsText())
            val builder = rig.find(rows, "sessions", "session_id", BUILDER)
            assertEquals(team.id, builder.getValue("team").jsonPrimitive.content)
        }
        serve(wired = false) { call ->
            val reply = call(HttpMethod.Get, "/api/teams", null, true)
            assertEquals(503, reply.status.value)
            assertEquals("""{"error":"the team store is not wired into this control plane"}""", reply.bodyAsText())
        }
    }
}
