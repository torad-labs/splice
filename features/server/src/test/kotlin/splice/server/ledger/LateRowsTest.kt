// What a Messages turn's row says when what is known of the turn came apart from its answer (docs/specs/server-mode.md,
// F5): a head whose row waits on a round its turn left streaming (code mode) is waited for, so the request is billed
// what it cost and never written off as one that billed nothing; and a turn belongs to the team its person was on
// when it arrived, the one whose budget it was admitted under.
package splice.server.ledger

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.server.ServedTokens
import splice.core.server.ServedTurn
import splice.core.turn.SERVED_TURN_HEADER
import splice.server.ServerMode
import splice.server.ServerRig
import splice.server.StandInHead
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal class LateRowsTest {
    @TempDir lateinit var state: Path

    private val rig = ServerRig()
    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()

    /** The id the listener named each turn by, as its head read it off the request. */
    private val named = LinkedBlockingQueue<String>()

    /** The head answers a turn once the test lets it; it starts let. */
    @Volatile
    private var goOn = CountDownLatch(0)

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            named += exchange.requestHeaders.getFirst(SERVED_TURN_HEADER).orEmpty()
            goOn.await(WITHIN_MS, TimeUnit.MILLISECONDS)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        start()
    }

    @AfterEach
    fun stop() {
        rig.close()
        head.stop(0)
        workers.shutdownNow()
    }

    private fun serve(): ServerMode = rig.open(state, listOf(StandInHead(head.address.port)))

    private fun send(key: String): CompletableFuture<HttpResponse<String>> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString("""{"model":"m","messages":[]}"""))
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The id of the turn the head was just asked. */
    private fun turnAtHead(): String =
        checkNotNull(named.poll(WITHIN_MS, TimeUnit.MILLISECONDS)) { "the turn never reached the head" }

    /** Every row the ledger file holds, oldest first. */
    private fun rows(): List<JsonObject> {
        val file = state.resolve("server").resolve("ledger.jsonl")
        if (!Files.exists(file)) return emptyList()
        return Files.readAllLines(file).filter(String::isNotBlank).map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun awaitRow(): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WITHIN_MS)
        while (rows().isEmpty()) {
            check(System.nanoTime() < deadline) { "the turn's row was never written" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(STEP_MS))
        }
        return rows().single()
    }

    private fun field(row: JsonObject, name: String): String = row.getValue(name).jsonPrimitive.content

    /** A head's word on a turn that billed [prompt] prompt tokens. */
    private fun billed(prompt: Long): ServedTurn = ServedTurn(
        model = "team-model",
        upstream = null,
        outcome = "ok",
        tokens = ServedTokens(prompt, cached = 200, completion = 30),
        costUsd = 0.25,
        ttftMs = 12,
        speed = null,
    )

    @Test
    fun `a turn whose head says its row waits on a round still streaming is billed what that row says`() {
        serve()
        val key = rig.keyFor("mia@team.example")

        assertEquals(200, send(key).get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())
        val turn = turnAtHead()
        rig.board.held(turn)

        // Past the moment a head that says nothing is waited for, the row still waits for this one.
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PAST_A_MOMENT_MS))
        assertTrue(rows().isEmpty(), "the row was written before its head said what the turn cost: ${rows()}")

        rig.board.ended(turn, billed(prompt = 1_200))
        val row = awaitRow()
        assertEquals("ok", field(row, "outcome"), row.toString())
        assertEquals("1200", field(row, "prompt_tokens"), row.toString())
        assertEquals("30", field(row, "completion_tokens"), row.toString())
        assertEquals("0.25", field(row, "cost_usd"), row.toString())
    }

    @Test
    fun `a row still waiting on its head when the server stops is written with what is known`() {
        serve()
        val key = rig.keyFor("mia@team.example")

        assertEquals(200, send(key).get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())
        rig.board.held(turnAtHead())
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PAST_A_MOMENT_MS))
        rig.close()

        val row = rows().single()
        assertEquals("mia@team.example", field(row, "person"))
        assertEquals("0", field(row, "prompt_tokens"), row.toString())
    }

    @Test
    fun `a turn stays on the team its person was on when it arrived, though they are moved before it ends`() {
        val mode = serve()
        val person = "mia@team.example"
        val key = rig.keyFor(person)
        runBlocking { mode.personApi.set(person, buildJsonObject { put("team", "alpha") }) }
        goOn = CountDownLatch(1)

        val sent = send(key)
        val turn = turnAtHead()
        runBlocking { mode.personApi.set(person, buildJsonObject { put("team", "beta") }) }
        goOn.countDown()
        assertEquals(200, sent.get(WITHIN_MS, TimeUnit.MILLISECONDS).statusCode())
        rig.board.ended(turn, billed(prompt = 10))

        assertEquals("alpha", field(awaitRow(), "team"))
    }
}

// The deadline for anything these tests wait to happen. Nothing waits this long unless the test is failing.
private const val WITHIN_MS = 20_000L

// Longer than the moment the ledger waits for a head that says nothing of its turn (two seconds).
private const val PAST_A_MOMENT_MS = 2_600L

private const val STEP_MS = 20L
