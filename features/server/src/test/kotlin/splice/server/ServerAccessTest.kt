// Which commands a team and a person may use on the server (docs/specs/server-mode.md, F12), as the operator sets them
// and as a team member's turns meet them: over real HTTP on loopback, in front of a head that serves two commands and
// counts every request that reaches it.
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.topology.ServerTeams
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal class ServerAccessTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val rig = ServerRig()

    /** How many requests reached the head. */
    private val reached = AtomicInteger()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            reached.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val answer = """{"type":"message","content":[{"type":"text","text":"from the box"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        start()
    }

    private lateinit var mode: ServerMode

    // The server serves `claude-team` and `claude-own`; team.example's people are on team core.
    @BeforeEach
    fun serve() {
        mode = rig.open(
            state,
            listOf(StandInHead(head.address.port), StandInHead(head.address.port, key = "own", command = OWN)),
            rig.config.copy(
                commands = listOf(TEAM, OWN),
                teams = ServerTeams(mapOf("team.example" to "core", "partner.example" to "partners")),
            ),
        )
    }

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
    }

    private fun turn(command: String, key: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/$command/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(TURN_BODY))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** [response] is the 403 `permission_error` Claude Code prints, naming [command], and nothing reached the head. */
    private fun assertForbidden(command: String, response: HttpResponse<String>, before: Int) {
        assertEquals(403, response.statusCode())
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        assertEquals("permission_error", error.getValue("type").jsonPrimitive.content)
        assertTrue(command in error.getValue("message").jsonPrimitive.content, error.toString())
        assertEquals(before, reached.get(), "a refused turn reached the head")
    }

    private fun setTeam(team: String, vararg commands: String): ServerAnswer = runBlocking {
        mode.teamApi.set(team, buildJsonObject { put("commands", buildJsonArray { commands.forEach { add(it) } }) })
    }

    private fun setPerson(person: String, body: JsonObject): ServerAnswer = runBlocking {
        mode.personApi.set(person, body)
    }

    @Test
    fun `someone neither a team nor their own setting names may use every command`() {
        setTeam("partners", OWN)
        val key = rig.keyFor("ada@team.example")
        assertEquals(200, turn(TEAM, key).statusCode())
        assertEquals(200, turn(OWN, key).statusCode())
    }

    @Test
    fun `a team's commands hold for its people, and another team's people are not held by them`() {
        assertInstanceOf(ServerAnswer.Done::class.java, setTeam("core", TEAM))
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, turn(TEAM, ada).statusCode())
        assertForbidden(OWN, turn(OWN, ada), reached.get())
        assertEquals(200, turn(OWN, rig.keyFor("pat@partner.example")).statusCode())
    }

    @Test
    fun `a person's own commands win over their team's, wider or narrower`() {
        setTeam("core", TEAM)
        setPerson("bea@team.example", buildJsonObject { put("commands", "all") })
        val bea = rig.keyFor("bea@team.example")
        assertEquals(200, turn(OWN, bea).statusCode())
        setPerson("cal@team.example", buildJsonObject { put("commands", buildJsonArray { add(OWN) }) })
        val cal = rig.keyFor("cal@team.example")
        assertEquals(200, turn(OWN, cal).statusCode())
        assertForbidden(TEAM, turn(TEAM, cal), reached.get())
    }

    @Test
    fun `a change holds from the person's next turn, with the key they already hold`() {
        setTeam("core", TEAM)
        val dan = rig.keyFor("dan@team.example")
        assertForbidden(OWN, turn(OWN, dan), reached.get())
        setTeam("core", TEAM, OWN)
        assertEquals(200, turn(OWN, dan).statusCode())
        setPerson("dan@team.example", buildJsonObject { put("commands", buildJsonArray { add(TEAM) }) })
        assertForbidden(OWN, turn(OWN, dan), reached.get())
    }

    @Test
    fun `a command the server does not serve, or no command at all, is refused and changes nothing`() {
        val unknown = assertInstanceOf(ServerAnswer.Refused::class.java, setTeam("core", "claude-nope"))
        assertTrue("claude-nope" in unknown.why && TEAM in unknown.why, unknown.why)
        assertInstanceOf(ServerAnswer.Refused::class.java, setTeam("core"))
        assertInstanceOf(ServerAnswer.Refused::class.java, setTeam("core", ""))
        val person = setPerson("eve@team.example", buildJsonObject { put("commands", buildJsonArray { add("nope") }) })
        assertInstanceOf(ServerAnswer.Refused::class.java, person)
        val eve = rig.keyFor("eve@team.example")
        assertEquals(200, turn(OWN, eve).statusCode())
    }

    @Test
    fun `a person's commands are answered as they now stand`() {
        val set = setPerson("fay@team.example", buildJsonObject { put("commands", "all") })
        val body = assertInstanceOf(ServerAnswer.Done::class.java, set).body
        assertEquals("all", body.getValue("commands").jsonPrimitive.content)
    }
}

private const val TEAM = "claude-team"
private const val OWN = "claude-own"
private const val TURN_BODY = """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
