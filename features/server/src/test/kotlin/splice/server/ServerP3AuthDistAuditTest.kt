// Server mode P3 (docs/specs/server-mode.md, F13, F15, F16):
//   - F13 forward_auth (`GET /server/forward-auth`) admits admins and allowlisted emails/domains via key or cookie
//   - F15 client distribution (`download_url` in `GET /server/profile` and `GET /server/install` bootstrap script)
//   - F16 audit log (`<state>/server/audit.jsonl` + `GET /server/api/audit`) records admin actions with actor & target
package splice.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerClient
import splice.core.topology.ServerForwardAuth
import splice.core.util.LogSink
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerP3AuthDistAuditTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val clock = SetClock()
    private val rig = ServerRig(LogSink {}, clock)

    @AfterEach
    fun stop() {
        rig.close()
    }

    @Test
    fun `forward_auth admits admins and allowlisted users via key or cookie and refuses others`() = runBlocking {
        val config = rig.config.copy(
            admins = listOf("ops@team.example"),
            forwardAuth = ServerForwardAuth(allow = listOf("ada@team.example", "@eng.example")),
        )
        val mode = rig.open(state, listOf(StandInHead(1)), config)
        val opsKey = mintKey(mode, "ops@team.example")
        val adaKey = mintKey(mode, "ada@team.example")
        val engKey = mintKey(mode, "dev@eng.example")
        val guestKey = mintKey(mode, "guest@other.example")

        val unauth = send("GET", "/server/forward-auth")
        assertEquals(401, unauth.statusCode())

        val denied = send("GET", "/server/forward-auth", "x-api-key" to guestKey)
        assertEquals(403, denied.statusCode())

        val okAdmin = send("GET", "/server/forward-auth", "Authorization" to "Bearer $opsKey")
        assertEquals(200, okAdmin.statusCode())
        assertEquals("ops@team.example", okAdmin.headers().firstValue("X-Splice-Person").orElse(""))
        assertEquals("true", okAdmin.headers().firstValue("X-Splice-Admin").orElse(""))

        val okCookie = send("GET", "/server/forward-auth", "Cookie" to "theme=dark; splice_forward_auth=$adaKey")
        assertEquals(200, okCookie.statusCode())
        assertEquals("ada@team.example", okCookie.headers().firstValue("X-Auth-Request-Email").orElse(""))
        assertEquals("false", okCookie.headers().firstValue("X-Splice-Admin").orElse(""))

        val okDomain = send("HEAD", "/server/forward-auth", "x-api-key" to engKey)
        assertEquals(200, okDomain.statusCode())
        assertEquals("dev@eng.example", okDomain.headers().firstValue("X-Splice-Person").orElse(""))
    }

    @Test
    fun `client distribution advertises download_url in profile and serves one-command install script`() = runBlocking {
        val config = rig.config.copy(
            client = ServerClient(minimum = "0.10.0", downloadUrl = "https://releases.team.example/splice"),
        )
        val mode = rig.open(state, listOf(StandInHead(1)), config)
        val key = mintKey(mode, "ada@team.example")

        val profileRes = send("GET", "/server/profile", "x-api-key" to key)
        assertEquals(200, profileRes.statusCode())
        val profile = Json.parseToJsonElement(profileRes.body()).jsonObject
        val client = profile["client"]!!.jsonObject
        assertEquals("0.10.0", client["minimum"]!!.jsonPrimitive.content)
        assertEquals("https://releases.team.example/splice", client["download_url"]!!.jsonPrimitive.content)

        val scriptRes = send("GET", "/server/install")
        assertEquals(200, scriptRes.statusCode())
        val script = scriptRes.body()
        assertTrue("SPLICE_RELEASE_BASE_URL='https://releases.team.example/splice'" in script, script)
        assertTrue("SPLICE_VERSION='0.10.0'" in script, script)
        assertTrue("splice remote add 'http://127.0.0.1:${rig.port}'" in script, script)
    }

    @Test
    fun `audit log records admin key, person, team and budget changes and serves them on admin API`() = runBlocking {
        val config = rig.config.copy(admins = listOf("ops@team.example"))
        val mode = rig.open(state, listOf(StandInHead(1)), config)
        val opsKey = mintKey(mode, "ops@team.example")

        val created = send(
            "POST",
            "/server/api/keys",
            "x-api-key" to opsKey,
            body = """{"person":"ada@team.example","name":"laptop"}""",
        )
        assertEquals(201, created.statusCode())
        val prefix = Json.parseToJsonElement(created.body()).jsonObject["prefix"]!!.jsonPrimitive.content
        assertEquals(200, send("DELETE", "/server/api/keys/$prefix", "x-api-key" to opsKey).statusCode())
        mode.personApi.set("ada@team.example", buildJsonObject { put("team", "core") })
        mode.teamApi.set("core", buildJsonObject { put("commands", Json.parseToJsonElement("""["claude-team"]""")) })
        mode.books.budgetApi.set(
            buildJsonObject {
                put("scope", "team")
                put("name", "core")
                put("daily_usd", 25.0)
            },
        )

        val auditRes = send("GET", "/server/api/audit?tail=20", "x-api-key" to opsKey)
        assertEquals(200, auditRes.statusCode())
        val entries = Json.parseToJsonElement(auditRes.body()).jsonObject["entries"]!!.jsonArray.map { it.jsonObject }
        val actions = entries.map { it["action"]!!.jsonPrimitive.content }
        assertEquals(listOf("key.create", "key.create", "key.revoke", "person.set", "team.set", "budget.set"), actions)
        assertEquals("ops@team.example", entries[1]["actor"]!!.jsonPrimitive.content)
        assertEquals("ops@team.example", entries[2]["actor"]!!.jsonPrimitive.content)
    }

    private suspend fun mintKey(mode: ServerMode, person: String): String {
        val created = mode.keyApi.create(buildJsonObject { put("person", person) }) as ServerAnswer.Done
        return created.body["key"]!!.jsonPrimitive.content
    }

    private fun send(
        method: String,
        path: String,
        vararg headers: Pair<String, String>,
        body: String? = null,
    ): HttpResponse<String> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}$path")).method(method, publisher)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
