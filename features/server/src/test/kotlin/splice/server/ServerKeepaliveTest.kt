// Server mode's keepalive (docs/specs/server-mode.md, F4) as a person's client meets it, over real HTTP on loopback:
// in front of a head that says nothing for a while, the way one does while its model server prefills a long prompt.
// No test here sits through that silence. The head holds a turn until the test lets it go on, the relay's clock is
// the test's (SteppedClock), and the client's answer is read as it comes (Hearing). Each test writes what the head
// does, says when time passes, and reads what the client has heard by then. One test waits out the relay's own one
// second on the real clock, because it has to tell that second from a keepalive's interval.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import splice.core.testing.TestPorts
import splice.core.topology.ServerLimits
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport

internal class ServerKeepaliveTest {
    @TempDir lateinit var state: Path

    private val clock = SteppedClock()
    private val rig = ServerRig(waiter = clock)
    private val port = rig.port

    /** The server on the real clock, for the one test that waits on it. */
    private val timed = ServerRig()
    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()

    /** A turn the head is holding, counted when it starts to; and the head's leave to go on with one, which the
     *  test gives. */
    private val holding = Semaphore(0)
    private val goOn = Semaphore(0)

    /** What the head does with each turn it is asked. */
    private fun interface HeadScript {
        fun answer(turn: Asked)
    }

    /** One turn at the head, for a script to answer. */
    private inner class Asked(private val exchange: HttpExchange) {
        /** Says nothing more until the test lets the head go on, as a head does while its model server prefills. */
        fun prefills() {
            holding.release()
            check(goOn.tryAcquire(HEARD_WITHIN_MS, TimeUnit.MILLISECONDS)) { "the test never let the head go on" }
        }

        fun opens(status: Int = 200, type: String = "text/event-stream") {
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, 0)
        }

        fun says(text: String) {
            exchange.responseBody.write(text.toByteArray())
            exchange.responseBody.flush()
        }

        fun ends() = exchange.close()
    }

    @Volatile
    private var script = HeadScript { it.ends() }

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            script.answer(Asked(exchange))
        }
        start()
    }

    /** Server mode as the daemon opens it, [on] the stepped clock's server unless the real one's is given, with
     *  `[server] keepalive_seconds` as given, and the key of one person. */
    private fun serve(
        keepaliveSeconds: Int,
        limits: ServerLimits = ServerLimits(),
        headPort: Int = head.address.port,
        on: ServerRig = rig,
    ): String {
        val server = on.config.copy(limits = limits, keepaliveSeconds = keepaliveSeconds)
        on.open(state, listOf(StandInHead(headPort)), server)
        return on.keyFor("lia@team.example")
    }

    @AfterEach
    fun stop() {
        rig.close()
        timed.close()
        head.stop(0)
        workers.shutdownNow()
    }

    /** A turn [key]'s holder sends, to the server on [to]. Its answer's headers come when the listener sends them. */
    private fun send(
        key: String,
        body: String = STREAMED,
        to: Int = port,
    ): CompletableFuture<HttpResponse<InputStream>> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$to/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    /** What the client hears of [sent] from its headers on, which have come by now. */
    private fun heard(sent: CompletableFuture<HttpResponse<InputStream>>): Hearing = hearing(sent, workers)

    /** Back once the head holds a turn and says nothing. */
    private fun headHolds() {
        assertTrue(holding.tryAcquire(HEARD_WITHIN_MS, TimeUnit.MILLISECONDS), "the turn never reached the head")
    }

    @Test
    fun `behind a silent head a streamed turn is opened in two seconds, kept alive each interval, then answered`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.prefills()
            turn.opens()
            turn.says(START)
            turn.says(DELTA)
            turn.says(STOP)
            turn.ends()
        }
        val sent = send(key)
        clock.pass()
        val heard = heard(sent)
        assertEquals(200, heard.status)
        assertEquals("text/event-stream", heard.type)
        assertTrue(keepalive(heard.block("a keepalive as its stream opens")))
        repeat(3) {
            clock.pass()
            assertTrue(keepalive(heard.block("a keepalive after one more interval of silence")))
        }
        goOn.release()
        assertEquals(START + DELTA + STOP, heard.rest("the head's answer"), "the head's events and nothing after them")
        val opened = clock.asked.first()
        assertTrue(opened <= 2_000, "the stream was opened after $opened ms of the head's silence")
        val intervals = clock.asked.drop(1)
        assertTrue(intervals.all { it <= 1_000 }, "with keepalive_seconds = 1 a keepalive waited longer: $intervals")
    }

    @Test
    fun `events are heard whole as the head ends them, and a silence inside a half-written one is kept alive`() {
        val key = serve(keepaliveSeconds = 1)
        val cut = DELTA.length / 2
        script = HeadScript { turn ->
            turn.opens()
            turn.says(START)
            turn.prefills()
            turn.says(DELTA.take(cut))
            turn.prefills()
            turn.says(DELTA.drop(cut) + STOP)
            turn.ends()
        }
        val heard = heard(send(key))
        assertEquals(START, heard.block("the first event, while the head has written no more"))
        clock.pass()
        assertTrue(keepalive(heard.block("a keepalive in the silence after the first event")))
        clock.awaitUntil("the relay waits out its next interval") { clock.parked.get() == 1 }
        val asked = clock.asked.size
        goOn.release()
        // The head has written half of its next event and fallen silent again. No reader acts on half an event, so
        // the relay holds it, and the stream stands between two events where the silence can be kept alive. Once the
        // relay has read the half it waits out a fresh interval, and the time that passes then is that silence.
        repeat(2) { headHolds() }
        clock.awaitUntil("the relay waits again after the half event") {
            clock.asked.size > asked && clock.parked.get() == 1
        }
        clock.pass()
        assertTrue(keepalive(heard.block("a keepalive in the silence inside the half-written event")))
        goOn.release()
        assertEquals(DELTA + STOP, heard.rest("the rest of the answer"), "the half event was not passed on whole")
    }

    @Test
    fun `a head that opens its stream and then says nothing has its client's stream opened a second in too`() {
        // A head's response headers are no answer yet. With a keepalive only each minute, a stream its client has by
        // the deadline was opened for the relay's one second, and not by a keepalive that happened to fall due.
        val key = serve(keepaliveSeconds = 60, on = timed)
        script = HeadScript { turn ->
            turn.opens()
            turn.prefills()
            turn.says(START + STOP)
            turn.ends()
        }
        val heard = heard(send(key, to = timed.port))
        assertEquals(200, heard.status)
        assertEquals("text/event-stream", heard.type)
        assertTrue(keepalive(heard.block("a keepalive as its stream opens")))
        goOn.release()
        assertEquals(START + STOP, heard.rest("the head's answer"), "the head's events and nothing after them")
    }

    @Test
    fun `a head that refuses at once is heard in its own status and words`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.opens(status = 400, type = "application/json")
            turn.says(REFUSAL)
            turn.ends()
        }
        val heard = heard(send(key))
        assertEquals(400, heard.status)
        assertEquals("application/json", heard.type)
        assertEquals(REFUSAL, heard.rest("the head's refusal"))
    }

    @Test
    fun `a head that refuses once the stream is open ends it with its own error`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.prefills()
            turn.opens(status = 529, type = "application/json")
            turn.says(OVERLOADED)
            turn.ends()
        }
        val sent = send(key)
        clock.pass()
        val heard = heard(sent)
        assertEquals(200, heard.status, "the stream was open before the head answered")
        assertTrue(keepalive(heard.block("a keepalive as its stream opens")))
        goOn.release()
        val event = heard.block("the head's refusal").orEmpty().lines()
        assertEquals("event: error", event.first())
        val said = Json.parseToJsonElement(event[1].removePrefix("data: "))
        assertEquals(Json.parseToJsonElement(OVERLOADED), said, "the head's own error, on an event's one data line")
        assertNull(heard.block("the end of the stream"), "the error ends the stream")
    }

    @Test
    fun `a head that goes away once the stream is open ends it saying the head is not answering`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.prefills()
            turn.ends()
        }
        val sent = send(key)
        clock.pass()
        val heard = heard(sent)
        assertEquals(200, heard.status)
        assertTrue(keepalive(heard.block("a keepalive as its stream opens")))
        goOn.release()
        val event = heard.block("that the head is not answering").orEmpty().lines()
        assertEquals("event: error", event.first())
        val error = Json.parseToJsonElement(event[1].removePrefix("data: ")).jsonObject.getValue("error").jsonObject
        assertEquals("api_error", error.getValue("type").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue("claude-team on gpu-box is not answering" in message, message)
    }

    @Test
    fun `a turn that asks for no stream answers one JSON body, however long its head is silent`() {
        val key = serve(keepaliveSeconds = 1)
        clock.flies = true
        script = HeadScript { turn ->
            turn.prefills()
            turn.opens(type = "application/json")
            turn.says(MESSAGE)
            turn.ends()
        }
        val sent = send(key, body = UNSTREAMED)
        headHolds()
        goOn.release()
        val heard = heard(sent)
        assertEquals(200, heard.status)
        assertEquals("application/json", heard.type)
        assertEquals(MESSAGE, heard.rest("the head's answer"), "one body, the head's own, with nothing before it")
    }

    @Test
    fun `with no keepalive_seconds a streamed turn waits on its head and carries its events alone`() {
        val key = serve(keepaliveSeconds = 0)
        clock.flies = true
        script = HeadScript { turn ->
            turn.prefills()
            turn.opens()
            turn.says(START + STOP)
            turn.ends()
        }
        val sent = send(key)
        headHolds()
        // Nothing signals that a stream was not opened, so one is watched for through a window: its length is how
        // long the test looks, and a slower machine only looks at less.
        assertThrows<TimeoutException>("a stream was opened before its head answered, with no keepalive set") {
            sent.get(NO_STREAM_WINDOW_MS, TimeUnit.MILLISECONDS)
        }
        goOn.release()
        assertEquals(START + STOP, heard(sent).rest("the head's events"))
    }

    @Test
    fun `a client that hangs up while its stream is kept alive is hung up on at the head, and frees its slot`() {
        // A head that takes one turn and never answers it: it reads until whoever sent the turn hangs up.
        val box = ServerSocket(TestPorts.reserve(), 1, InetAddress.getLoopbackAddress())
        val hungUp = CompletableFuture.supplyAsync(
            {
                box.accept().use { turn ->
                    try {
                        turn.getInputStream().readAllBytes().size
                    } catch (_: IOException) {
                        HUNG_UP_HARD
                    }
                }
            },
            workers,
        )
        val key = serve(keepaliveSeconds = 1, limits = ServerLimits(inflightPerPerson = 1), headPort = box.localPort)
        Socket("127.0.0.1", port).use { client ->
            client.soTimeout = HEARD_WITHIN_MS.toInt()
            val request = "POST /claude-team/v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-api-key: $key\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${STREAMED.length}\r\n\r\n$STREAMED"
            client.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            clock.pass()
            val opened = client.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 200 OK", opened, "the stream of a turn whose head is silent")
            assertEquals(429, statusOf(key), "the turn in flight does not fill the cap of one")
        }
        hungUp.get(HEARD_WITHIN_MS, TimeUnit.MILLISECONDS)
        // The head is gone now, so a turn that takes the freed slot is told so, where one still capped is a 429.
        box.close()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HEARD_WITHIN_MS)
        while (statusOf(key) != HEAD_GONE) {
            assertTrue(System.nanoTime() < deadline, "the slot of the client that hung up was never freed")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }

    /** The status a turn of [key]'s that asks for no stream is answered with. */
    private fun statusOf(key: String): Int = heard(send(key, body = UNSTREAMED)).status
}

private const val STREAMED = """{"model":"m","stream":true,"messages":[]}"""
private const val UNSTREAMED = """{"model":"m","messages":[]}"""

private const val START = "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
private const val DELTA =
    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n"
private const val STOP = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"

private const val MESSAGE = """{"type":"message","content":[{"type":"text","text":"from the box"}]}"""
private const val REFUSAL = """{"type":"error","error":{"type":"invalid_request_error","message":"no such model"}}"""

// As a head may write it: on more than one line, which an event's data may not be.
private const val OVERLOADED =
    "{\"type\":\"error\",\n \"error\":{\"type\":\"overloaded_error\",\"message\":\"the box is busy\"}}"

// The pause between two looks at a slot that is being freed, inside a poll that has a deadline.
private const val POLL_MS = 50L

// What the stand-in head's read answers when the relay's hang-up reached it as a reset.
private const val HUNG_UP_HARD = -1

// The status of a turn whose head does not answer.
private const val HEAD_GONE = 503
