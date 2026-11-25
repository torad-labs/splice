// What a person's last turn counts toward when they ask again the moment its answer has come (docs/specs/server-mode.md,
// F5 and F12): a client that waits for the whole answer and asks again at once is held to what that answer cost, by its
// budget and by its tokens a minute. Over real HTTP on loopback, through the team's door, whose relay goes on a moment
// after its answer is out, as a relay that hangs up on its upstream after answering does; its head bills every turn a
// dollar, 1200 prompt tokens and 30 of answer.
package splice.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import splice.core.topology.ServerLimits
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path

internal class ServerUsageOrderTest {
    @TempDir lateinit var state: Path

    private val http = HttpClient.newHttpClient()
    private val door = DoorStandIn().apply {
        billed = ServedTokens(prompt = 1_200, cached = 200, completion = 30) to 1.0
        finishingMs = FINISHING_MS
    }
    private val rig = ServerRig(gateway = door).also { door.board = it.board }

    @AfterEach
    fun stop() {
        rig.close()
    }

    private fun turn(key: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/door/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(TURN_BODY))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** The Anthropic error Claude Code prints: its type and its message. */
    private fun error(response: HttpResponse<String>): Pair<String, String> {
        val error = Json.parseToJsonElement(response.body()).jsonObject.getValue("error").jsonObject
        return error.getValue("type").jsonPrimitive.content to error.getValue("message").jsonPrimitive.content
    }

    @Test
    fun `a turn asked the moment the last answer came is refused by the budget that answer spent`() {
        val mode = rig.open(state, emptyList())
        val budget = buildJsonObject {
            put("scope", "person")
            put("name", "ada@team.example")
            put("daily_usd", 1.0)
        }
        assertInstanceOf(ServerAnswer.Done::class.java, runBlocking { mode.books.budgetApi.set(budget) })
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, turn(ada).statusCode())
        val next = turn(ada)
        assertEquals(400, next.statusCode(), "the next turn was served past the budget the last one spent")
        assertTrue("daily budget of ada@team.example is spent" in error(next).second, next.body())
    }

    @Test
    fun `a turn asked the moment the last answer came waits on the tokens that answer used`() {
        rig.open(state, emptyList(), rig.config.copy(limits = ServerLimits(tpmPerPerson = 1_000)))
        val ada = rig.keyFor("ada@team.example")
        assertEquals(200, turn(ada).statusCode())
        val next = turn(ada)
        assertEquals(429, next.statusCode(), "the next turn was served past the tokens the last one used")
        assertEquals("rate_limit_error", error(next).first, next.body())
    }
}

// Far longer than a client on loopback takes to read an answer and ask again, and well inside the moment the listener
// waits for a row still being written.
private const val FINISHING_MS = 500L

private const val TURN_BODY = """{"model":"box-model","max_tokens":8,"messages":[{"role":"user","content":"hi"}]}"""
