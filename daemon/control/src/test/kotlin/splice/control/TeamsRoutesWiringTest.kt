// NEW: V4-131 — every team and project route answers THROUGH A REAL ControlServer over HTTP with the
// mgmt key. TeamsRoutesTest and ProjectsRoutesTest prove the payloads; this proves the routing lines
// and the `teams` property ControlPlane assigns: delete a routing line and its route answers 404 here,
// by name. It also pins what must NOT be routed: there is no GET /api/teams/{id} (FEATURES.md 6.1).
package splice.control

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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.config.ConfigService
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.nio.file.Path

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

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
        val port = ServerSocket(0).use { it.localPort }
        val control = ControlServer(
            port = port,
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
        control.start()
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
