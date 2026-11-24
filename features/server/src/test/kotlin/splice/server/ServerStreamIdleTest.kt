// Server mode's stream idle deadline (#791, `[server] stream_idle_seconds`) as a person's client meets it, over real
// HTTP on loopback: in front of a head whose answer starts and then falls silent, as one does when its model server
// stalls mid-answer. No test sits through the silence: the relay's clock is the test's (SteppedClock), and the client's
// answer is read as it comes (Hearing).
package splice.server

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

internal class ServerStreamIdleTest {
    @TempDir lateinit var state: Path

    private val clock = SteppedClock()
    private val rig = ServerRig(waiter = clock)
    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()

    /** The head's leave to go on after it has started its answer and fallen silent, which the test gives. */
    private val goOn = Semaphore(0)

    /** A head that starts its answer, a message and one word of it, falls silent until the test lets it go on, and
     *  then ends it. */
    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = workers
        createContext("/v1/messages") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val body = exchange.responseBody
            body.write((START + DELTA).toByteArray())
            body.flush()
            check(goOn.tryAcquire(HEARD_WITHIN_MS, TimeUnit.MILLISECONDS)) { "the test never let the head go on" }
            exchange.use {
                body.write(STOP.toByteArray())
                body.flush()
            }
        }
        start()
    }

    private fun serve(streamIdleSeconds: Int): String {
        val server = rig.config.copy(streamIdleSeconds = streamIdleSeconds)
        rig.open(state, listOf(StandInHead(head.address.port)), server)
        return rig.keyFor("lia@team.example")
    }

    @AfterEach
    fun stop() {
        goOn.release()
        rig.close()
        head.stop(0)
        workers.shutdownNow()
    }

    private fun send(key: String): CompletableFuture<HttpResponse<InputStream>> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${rig.port}/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(STREAMED))
        return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream())
    }

    @Test
    fun `a stream that falls silent after its first word ends with an error event once stream_idle_seconds pass`() {
        val heard = hearing(send(serve(streamIdleSeconds = 30)), workers)
        assertEquals(200, heard.status)
        assertEquals(START, heard.block("the message's start"))
        assertEquals(DELTA, heard.block("the answer's first word"))

        clock.pass()
        val event = heard.block("the error that ends the silent stream").orEmpty().lines()
        assertEquals("event: error", event.first(), "the silent stream did not end with an error event: $event")
        val error = Json.parseToJsonElement(event[1].removePrefix("data: ")).jsonObject.getValue("error").jsonObject
        assertEquals("api_error", error.getValue("type").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue(listOf("said nothing for 30s", "stream_idle_seconds = 30").all(message::contains), message)
        assertNull(heard.block("the end of the stream"), "the error does not end the stream")
        assertEquals(listOf(30_000L), clock.asked, "the silence was not timed by stream_idle_seconds")
    }

    @Test
    fun `with no stream_idle_seconds a silent stream is waited on however long, and its answer goes on`() {
        val heard = hearing(send(serve(streamIdleSeconds = 0)), workers)
        // Every wait the relay might ask for ends at once, as if any length of time had passed.
        clock.flies = true
        assertEquals(START, heard.block("the message's start"))
        assertEquals(DELTA, heard.block("the answer's first word"))
        goOn.release()
        assertEquals(STOP, heard.rest("the rest of the answer"), "the silent stream was ended before its answer")
        assertTrue(clock.asked.isEmpty(), "a silence was timed with no stream_idle_seconds: ${clock.asked}")
    }
}

private const val STREAMED = """{"model":"m","stream":true,"messages":[]}"""

private const val START = "event: message_start\ndata: {\"type\":\"message_start\"}\n\n"
private const val DELTA =
    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"hi\"}}\n\n"
private const val STOP = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
