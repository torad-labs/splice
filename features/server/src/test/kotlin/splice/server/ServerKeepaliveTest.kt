// Server mode's keepalive (docs/specs/server-mode.md, F4) as a person's client meets it, over real HTTP on loopback:
// in front of a head that says nothing for a while, the way one does while its model server prefills a long prompt.
// Each test writes what the head does and reads what the client then hears, and when.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
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
import splice.core.config.MgmtKey
import splice.core.config.StatePaths
import splice.core.config.TurnKey
import splice.core.testing.TestPorts
import splice.core.topology.ServerAddress
import splice.core.topology.ServerConfig
import splice.core.topology.ServerLimits
import splice.core.util.LogSink
import splice.launch.LaunchSpec
import splice.share.SharedHead
import splice.share.SharedHeads
import java.io.IOException
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

internal class ServerKeepaliveTest {
    @TempDir lateinit var state: Path

    private val port = TestPorts.reserve()
    private val http = HttpClient.newHttpClient()
    private val workers = Executors.newCachedThreadPool()
    private var mode: ServerMode? = null

    /** What the head does with each turn it is asked. */
    private fun interface HeadScript {
        fun answer(turn: Asked)
    }

    /** One turn at the head, for a script to answer in its own time. */
    private class Asked(private val exchange: HttpExchange) {
        fun silent(ms: Long) = LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ms))

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

    private class Head(override val port: Int) : SharedHead {
        override val key: String = "team"
        override val command: String = "claude-team"

        override fun running(): Boolean = true

        override fun launch(): LaunchSpec? = null

        override fun logins(): List<String> = emptyList()

        override fun pin(login: String, session: String): Boolean = false

        override fun maxRequestBytes(): Int = 1_000_000

        override fun requestReadTimeoutMs(): Long = 5_000
    }

    /** Server mode as the daemon opens it, with `[server] keepalive_seconds` as given, and the key of one person. */
    private fun serve(
        keepaliveSeconds: Int,
        limits: ServerLimits = ServerLimits(),
        headPort: Int = head.address.port,
    ): String {
        val machine = object : SharedHeads {
            override val machine: String = "gpu-box"

            override fun offered(): List<SharedHead> = listOf(Head(headPort))
        }
        val server = ServerConfig(
            listen = "127.0.0.1:$port",
            commands = listOf("claude-team"),
            limits = limits,
            keepaliveSeconds = keepaliveSeconds,
        )
        val reach = ServerReach(TurnKey(MgmtKey(StatePaths(baseOverride = state))), TestPorts.reserve())
        val opened = ServerMode(server, state.resolve("server"), Dispatchers.IO, LogSink { })
        opened.open(ServerAddress("127.0.0.1", port), machine, reach, state)
        mode = opened
        val minted = runBlocking { opened.keyApi.create(buildJsonObject { put("person", "lia@team.example") }) }
        return assertInstanceOf(ServerAnswer.Done::class.java, minted).body.getValue("key").jsonPrimitive.content
    }

    @AfterEach
    fun stop() {
        runBlocking { mode?.close() }
        head.stop(0)
        workers.shutdownNow()
    }

    /** What a client heard of one turn: its status and type, when its headers came, and each read of its body with
     *  when it came, in milliseconds from the moment the turn was sent. */
    private class Heard(val status: Int, val type: String, val headersMs: Long, val reads: List<Pair<Long, String>>) {
        val body: String = reads.joinToString("") { it.second }

        /** The body in the blocks a blank line ends: an event each, or a comment. */
        private val blocks = body.split("\n\n").filter(String::isNotEmpty)

        /** The comments: blocks whose every line an SSE reader skips. */
        val keepalives: Int = blocks.count(::comment)

        /** The body as an SSE reader takes it, the comments gone. */
        val events: String = blocks.filterNot(::comment).joinToString("") { "$it\n\n" }

        /** The longest the client heard nothing between two reads of the body. */
        val longestSilenceMs: Long =
            reads.map { it.first }.zipWithNext { earlier, later -> later - earlier }.maxOrNull() ?: 0L

        private fun comment(block: String): Boolean = block.lines().all { it.startsWith(":") }
    }

    private fun turn(key: String, body: String = STREAMED): Heard {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/claude-team/v1/messages"))
            .header("Content-Type", "application/json")
            .header("x-api-key", key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
        val sent = System.nanoTime()
        val response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
        val headersMs = msSince(sent)
        val reads = mutableListOf<Pair<Long, String>>()
        response.body().use { input ->
            val buffer = ByteArray(READ_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                reads += msSince(sent) to String(buffer, 0, read)
            }
        }
        val type = response.headers().firstValue("content-type").orElse("").substringBefore(';')
        return Heard(response.statusCode(), type, headersMs, reads)
    }

    private fun msSince(nanos: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos)

    @Test
    fun `behind a silent head a streamed turn has its stream at once, a keepalive each interval, then its answer`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.silent(3_300)
            turn.opens()
            turn.says(START)
            turn.says(DELTA)
            turn.says(STOP)
            turn.ends()
        }
        val heard = turn(key)
        assertEquals(200, heard.status)
        assertEquals("text/event-stream", heard.type)
        assertTrue(heard.headersMs < 2_000, "the headers came after ${heard.headersMs} ms")
        assertTrue(heard.reads.first().first < 2_000, "the first keepalive came after ${heard.reads.first().first} ms")
        assertTrue(heard.longestSilenceMs < 1_400, "with a keepalive each second: ${heard.reads.map { it.first }}")
        assertTrue(heard.keepalives >= 3, "3.3 s of silence carried ${heard.keepalives} keepalives")
        assertEquals(START + DELTA + STOP, heard.events, "the client's reader takes the head's events and nothing else")
        assertTrue(heard.body.endsWith(STOP), heard.body)
    }

    @Test
    fun `the silence between two events is kept alive, and an event the head has half written is left whole`() {
        val key = serve(keepaliveSeconds = 1)
        val cut = DELTA.length / 2
        script = HeadScript { turn ->
            turn.opens()
            turn.says(START)
            turn.silent(2_300)
            turn.says(DELTA.take(cut))
            turn.silent(2_300)
            turn.says(DELTA.drop(cut) + STOP)
            turn.ends()
        }
        val heard = turn(key)
        val first = heard.reads.first()
        assertTrue(first.first < 1_000 && START in first.second, "an event waits for the answer's end: $first")
        assertTrue(heard.reads.last().first - first.first > 4_000, "the events came together: ${heard.reads}")
        val between = heard.body.substringAfter(START).substringBefore(DELTA.take(cut))
        assertTrue(": " in between, "2.3 s between two events carried no keepalive: ${heard.body}")
        assertTrue(DELTA in heard.body, "a keepalive was written inside an event: ${heard.body}")
        assertEquals(START + DELTA + STOP, heard.events)
    }

    @Test
    fun `a head that refuses at once is heard in its own status and words`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.opens(status = 400, type = "application/json")
            turn.says(REFUSAL)
            turn.ends()
        }
        val heard = turn(key)
        assertEquals(400, heard.status)
        assertEquals("application/json", heard.type)
        assertEquals(REFUSAL, heard.body)
    }

    @Test
    fun `a head that refuses once the stream is open ends it with its own error`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.silent(1_800)
            turn.opens(status = 529, type = "application/json")
            turn.says(OVERLOADED)
            turn.ends()
        }
        val heard = turn(key)
        assertEquals(200, heard.status, "the stream was open before the head answered")
        assertTrue(heard.keepalives >= 1, heard.body)
        val event = heard.events.lines()
        assertEquals("event: error", event.first(), heard.body)
        val said = Json.parseToJsonElement(event[1].removePrefix("data: "))
        assertEquals(Json.parseToJsonElement(OVERLOADED), said, "the head's own error, on an event's one data line")
    }

    @Test
    fun `a head that goes away once the stream is open ends it saying the head is not answering`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.silent(1_800)
            turn.ends()
        }
        val heard = turn(key)
        assertEquals(200, heard.status)
        val event = heard.events.lines()
        assertEquals("event: error", event.first(), heard.body)
        val error = Json.parseToJsonElement(event[1].removePrefix("data: ")).jsonObject.getValue("error").jsonObject
        assertEquals("api_error", error.getValue("type").jsonPrimitive.content)
        val message = error.getValue("message").jsonPrimitive.content
        assertTrue("claude-team on gpu-box is not answering" in message, message)
    }

    @Test
    fun `a turn that asks for no stream answers one JSON body, however long its head is silent`() {
        val key = serve(keepaliveSeconds = 1)
        script = HeadScript { turn ->
            turn.silent(2_300)
            turn.opens(type = "application/json")
            turn.says(MESSAGE)
            turn.ends()
        }
        val heard = turn(key, body = UNSTREAMED)
        assertEquals(200, heard.status)
        assertEquals("application/json", heard.type)
        assertEquals(MESSAGE, heard.body, "one body, the head's own, with nothing before it")
    }

    @Test
    fun `with no keepalive_seconds a streamed turn waits on its head and carries its events alone`() {
        val key = serve(keepaliveSeconds = 0)
        script = HeadScript { turn ->
            turn.silent(1_600)
            turn.opens()
            turn.says(START + STOP)
            turn.ends()
        }
        val heard = turn(key)
        assertTrue(heard.headersMs >= 1_500, "a stream was opened after ${heard.headersMs} ms with no keepalive set")
        assertEquals(START + STOP, heard.body)
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
            val request = "POST /claude-team/v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nx-api-key: $key\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${STREAMED.length}\r\n\r\n$STREAMED"
            client.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val opened = client.getInputStream().bufferedReader().readLine()
            assertEquals("HTTP/1.1 200 OK", opened, "the stream of a turn whose head is silent")
            assertEquals(429, turn(key, body = UNSTREAMED).status, "the turn in flight does not fill the cap of one")
        }
        hungUp.get(FREED_WITHIN_SECONDS, TimeUnit.SECONDS)
        // The head is gone now, so a turn that takes the freed slot is told so, where one still capped is a 429.
        box.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FREED_WITHIN_SECONDS)
        while (turn(key, body = UNSTREAMED).status != HEAD_GONE) {
            assertTrue(System.nanoTime() < deadline, "the slot of the client that hung up was never freed")
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(POLL_MS))
        }
    }
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

private const val READ_BYTES = 8 * 1024
private const val FREED_WITHIN_SECONDS = 10L
private const val POLL_MS = 50L

// What the stand-in head's read answers when the relay's hang-up reached it as a reset.
private const val HUNG_UP_HARD = -1

// The status of a turn whose head does not answer.
private const val HEAD_GONE = 503
