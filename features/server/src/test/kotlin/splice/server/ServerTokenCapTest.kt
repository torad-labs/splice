// A person's tokens a minute on the server (docs/specs/server-mode.md, F12): `[server.limits] tpm_per_person`, and a
// person's own `--tpm` over it, as a team member's turns meet them. Over real HTTP on loopback, in front of a head that
// bills every turn 1200 prompt tokens and 30 of answer, on a clock the test moves.
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.server.TurnPace
import splice.core.topology.ServerLimits
import splice.core.turn.SERVED_TURN_HEADER
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal class ServerTokenCapTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val clock = SetClock()
    private val rig = ServerRig(clock = clock)

    /** How many turns reached the head. */
    private val reached = AtomicInteger()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/messages") { exchange ->
            reached.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            // As a head does once its turn's row is written: it says on the board what the turn billed.
            val id = exchange.requestHeaders.getFirst(SERVED_TURN_HEADER)
            if (id != null) rig.board.ended(id, BILLED)
            val answer = """{"type":"message","content":[{"type":"text","text":"from the box"}]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        start()
    }

    private fun serve(): ServerMode = rig.open(
        state,
        listOf(StandInHead(head.address.port)),
        rig.config.copy(limits = ServerLimits(tpmPerPerson = CAP)),
    )

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
    }

    private fun turn(key: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(TURN_BODY))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** [response] is the 429 `rate_limit_error` Claude Code waits out, with a Retry-After of a minute at most, and the
     *  turn never reached the head. */
    private fun assertHeld(response: HttpResponse<String>, before: Int) {
        assertEquals(429, response.statusCode(), response.body())
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        assertEquals("rate_limit_error", error.getValue("type").jsonPrimitive.content)
        val after = response.headers().firstValue("retry-after").map(String::toLong).orElse(0)
        assertTrue(after in 1..60, "Retry-After was $after")
        assertEquals(before, reached.get(), "a held turn reached the head")
    }

    private fun setPerson(mode: ServerMode, person: String, body: JsonObject): ServerAnswer = runBlocking {
        mode.personApi.set(person, body)
    }

    @Test
    fun `once a person's last minute reaches the cap their next turn waits, and others are served`() {
        serve()
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, turn(ada).statusCode())
        assertEquals(200, turn(ada).statusCode())
        assertHeld(turn(ada), reached.get())
        assertEquals(200, turn(rig.keyFor("bob@team.example")).statusCode())
    }

    @Test
    fun `a person is served again once their turns have left the minute`() {
        serve()
        val ada = rig.keyFor("ada@team.example")
        repeat(2) { assertEquals(200, turn(ada).statusCode()) }
        val held = turn(ada)
        assertHeld(held, reached.get())
        clock.pass(held.headers().firstValue("retry-after").get().toLong())
        assertEquals(200, turn(ada).statusCode())
    }

    @Test
    fun `a restart forgets none of the minute`() {
        serve()
        val ada = rig.keyFor("ada@team.example")
        repeat(2) { assertEquals(200, turn(ada).statusCode()) }
        rig.close()
        serve()
        assertHeld(turn(ada), reached.get())
    }

    @Test
    fun `a person's own cap holds over the server's, narrower or wider`() {
        val mode = serve()
        assertInstanceOf(ServerAnswer.Done::class.java, setPerson(mode, "cy@team.example", tpm(1_000)))
        val cy = rig.keyFor("cy@team.example")
        assertEquals(200, turn(cy).statusCode())
        assertHeld(turn(cy), reached.get())
        setPerson(mode, "dee@team.example", tpm(100_000))
        val dee = rig.keyFor("dee@team.example")
        repeat(3) { assertEquals(200, turn(dee).statusCode()) }
    }

    @Test
    fun `a cap that is not a whole number above 0 is refused`() {
        val mode = serve()
        assertInstanceOf(ServerAnswer.Refused::class.java, setPerson(mode, "gil@team.example", tpm(0)))
        assertInstanceOf(ServerAnswer.Refused::class.java, setPerson(mode, "gil@team.example", tpm(-5)))
        val lots = setPerson(mode, "gil@team.example", buildJsonObject { put("tpm", "lots") })
        assertInstanceOf(ServerAnswer.Refused::class.java, lots)
    }

    private fun tpm(cap: Int): JsonObject = buildJsonObject { put("tpm", cap) }
}

// Two turns of 1230 tokens pass it; one does not.
private const val CAP = 2_000

private val BILLED = ServedTurn(
    model = "team-model",
    upstream = null,
    outcome = "ok",
    tokens = ServedTokens(prompt = 1_200, cached = 200, completion = 30),
    costUsd = null,
    pace = TurnPace(ttftMs = 12),
    speed = null,
)

private const val TURN_BODY = """{"model":"team-model","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
