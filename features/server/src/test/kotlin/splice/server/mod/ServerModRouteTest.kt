// `/server/mod/<name>/...` as a stranger, a team member and an admin meet it (docs/specs/server-mode.md, "Mode
// routes"): over real HTTP on loopback, in front of a stand-in for the daemon's modes that answers with what it was
// handed, so a test reads exactly what a mode would see.
package splice.server.mod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.ModAnswer
import splice.core.server.ModRequest
import splice.core.server.ModResponse
import splice.core.server.ServerModRoutes
import splice.core.topology.ServerConfig
import splice.core.topology.ServerTeams
import splice.server.ServerRig
import splice.server.StandInHead
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

internal class ServerModRouteTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val rig = ServerRig()

    /** Every request a mode was handed, in order. */
    private val handed = CopyOnWriteArrayList<ModRequest>()

    /** `harbor` serves anyone with a key, `vault` only admins, `ghost` is off, and `throws` fails; `harbor` answers
     *  `GET tide` with who asked and echoes `POST echo`, and has no other path. */
    private val modes = object : ServerModRoutes {
        override fun answer(name: String, request: ModRequest): ModAnswer {
            handed += request
            return when {
                name == "vault" && !request.caller.admin -> ModAnswer.AdminsOnly
                name == "throws" -> ModAnswer.Failed("mode throws failed: IllegalStateException")
                name == "harbor" || name == "vault" -> ModAnswer.Answered(harbor(request))
                else -> ModAnswer.NoSuchMod
            }
        }

        private fun harbor(request: ModRequest): ModResponse? = when (request.path) {
            listOf("tide") -> {
                val caller = request.caller
                val body = """{"person":"${caller.person}","team":"${caller.team}","admin":${caller.admin}}"""
                ModResponse(200, "application/json", body.toByteArray())
            }
            listOf("echo") -> ModResponse(201, "text/plain; charset=utf-8", request.body)
            else -> null
        }
    }

    @AfterEach
    fun stop() {
        rig.close()
    }

    private fun serve(server: ServerConfig = rig.config) {
        rig.mods = modes
        rig.open(state, listOf(StandInHead(port = 1)), server)
    }

    private fun send(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$path")).method(method, publisher)
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun error(response: HttpResponse<String>): Pair<String, String> {
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    @Test
    fun `a mod path asks for a key before any mode is reached`() {
        serve()
        val refused = send("GET", "/server/mod/harbor/tide")
        assertEquals(401, refused.statusCode())
        assertEquals("authentication_error", error(refused).first)
        val unknown = send("GET", "/server/mod/harbor/tide", mapOf("x-api-key" to "sk-splice_nobody_secret"))
        assertEquals(401, unknown.statusCode())
        assertTrue(handed.isEmpty(), "a mode was handed a request no key opened")
    }

    @Test
    fun `a key opens a mode as x-api-key and as a bearer, and the mode reads who asked and their team`() {
        serve(rig.config.copy(teams = ServerTeams(byDomain = mapOf("team.example" to "core"))))
        val key = rig.keyFor("Ada@Team.example")
        val asApiKey = send("GET", "/server/mod/harbor/tide", mapOf("x-api-key" to key))
        assertEquals(200, asApiKey.statusCode(), asApiKey.body())
        assertEquals("""{"person":"ada@team.example","team":"core","admin":false}""", asApiKey.body())
        val asBearer = send("GET", "/server/mod/harbor/tide", mapOf("Authorization" to "Bearer $key"))
        assertEquals(200, asBearer.statusCode(), asBearer.body())
        assertEquals("application/json", asBearer.headers().firstValue("Content-Type").get().substringBefore(';'))
    }

    @Test
    fun `a mode is handed its path, query, body and a few headers, and never the key or a cookie`() {
        serve()
        val key = rig.keyFor("ada@team.example")
        val headers = mapOf(
            "Authorization" to "Bearer $key",
            "Cookie" to "splice_session=$key",
            "Content-Type" to "text/plain",
            "X-Forwarded-For" to "10.0.0.9",
        )
        val echoed = send("POST", "/server/mod/harbor/echo?tide=high&tide=low", headers, "a message in a bottle")
        assertEquals(201, echoed.statusCode(), echoed.body())
        assertEquals("a message in a bottle", echoed.body())

        val request = handed.single()
        assertEquals("POST", request.method)
        assertEquals(listOf("echo"), request.path)
        assertEquals(mapOf("tide" to "high"), request.query)
        assertEquals("text/plain", request.headers["content-type"])
        listOf("authorization", "x-api-key", "cookie", "x-forwarded-for").forEach {
            assertFalse(it in request.headers, "a mode was handed the $it header")
        }
        val seen = listOf(request.method, request.path, request.caller, request.headers, request.query, request.text())
            .joinToString("\n")
        val secret = key.substringAfterLast('_')
        assertFalse(key in seen || secret in seen, "a mode was handed the key:\n$seen")
    }

    @Test
    fun `an admins-only mode answers an admin's key and refuses a member's in the listener's voice`() {
        serve(rig.config.copy(admins = listOf("ops@team.example")))
        val member = send("GET", "/server/mod/vault/tide", mapOf("x-api-key" to rig.keyFor("ada@team.example")))
        assertEquals(403, member.statusCode())
        val (type, message) = error(member)
        assertEquals("permission_error", type)
        assertTrue("ada@team.example" in message && "admins" in message, message)

        val admin = send("GET", "/server/mod/vault/tide", mapOf("x-api-key" to rig.keyFor("Ops@Team.example")))
        assertEquals(200, admin.statusCode(), admin.body())
        assertTrue(""""admin":true""" in admin.body(), admin.body())
    }

    @Test
    fun `an admin outside the server admin networks cannot use an admins-only mode`() {
        serve(rig.config.copy(admins = listOf("ops@team.example"), adminCidrs = listOf("192.0.2.0/24")))
        val key = mapOf("x-api-key" to rig.keyFor("ops@team.example"))
        val denied = send("GET", "/server/mod/vault/tide", key)
        assertEquals(403, denied.statusCode())
        val (type, message) = error(denied)
        assertEquals("permission_error", type)
        assertTrue("not allowed from this address" in message, message)
        assertFalse("not one of them" in message, message)
        val publicMode = send("GET", "/server/mod/harbor/tide", key)
        assertEquals(200, publicMode.statusCode())
        assertTrue(""""admin":false""" in publicMode.body(), publicMode.body())
    }

    @Test
    fun `a mode that does not serve here, a path it lacks and a mode that throws are each answered in errors`() {
        serve()
        val key = mapOf("x-api-key" to rig.keyFor("ada@team.example"))
        val off = send("GET", "/server/mod/ghost/tide", key)
        assertEquals(404, off.statusCode())
        assertEquals("not_found_error", error(off).first)
        assertEquals(404, send("GET", "/server/mod/harbor/sunk", key).statusCode())
        assertEquals(404, send("GET", "/server/mod", key).statusCode())
        val threw = send("GET", "/server/mod/throws/tide", key)
        assertEquals(500, threw.statusCode())
        assertTrue("IllegalStateException" in error(threw).second, threw.body())
    }

    @Test
    fun `a body past the cap is refused before a mode is handed it`() {
        serve()
        val key = mapOf("x-api-key" to rig.keyFor("ada@team.example"))
        val large = send("POST", "/server/mod/harbor/echo", key, "x".repeat(1024 * 1024 + 1))
        assertEquals(413, large.statusCode())
        assertTrue(handed.isEmpty(), "a mode was handed a body past the cap")
    }

    @Test
    fun `a server whose daemon offers no modes has no mod paths`() {
        rig.open(state, listOf(StandInHead(port = 1)))
        val key = mapOf("x-api-key" to rig.keyFor("ada@team.example"))
        val none = send("GET", "/server/mod/harbor/tide", key)
        assertEquals(404, none.statusCode())
        assertEquals("not_found_error", error(none).first)
    }
}
