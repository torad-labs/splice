package splice.app.mcp.stdio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import java.io.BufferedReader
import java.io.OutputStream
import java.io.Reader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class McpStdioBridgeTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
    }
    private val seen = ConcurrentLinkedQueue<Seen>()
    private val queries = ConcurrentLinkedQueue<String>()

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `streamable http carries the bearer, session and protocol version and answers every message on one line`() {
        server.createContext("/mcp/hosted") { exchange -> streamable(exchange) }
        server.start()
        val target = McpStdioTarget(url("/mcp/hosted"), mapOf("Authorization" to "Bearer SECRET-MCP-KEY"))
        val errLines = ConcurrentLinkedQueue<String>()
        val run = Bridged(McpStreamableHttp(target, HttpClient.newHttpClient(), Dispatchers.IO), errLines)

        run.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""")
        val initialized = run.next()
        assertEquals("2025-11-25", initialized["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        run.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        run.send("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val listed = run.next()
        assertEquals(2, listed["id"]!!.jsonPrimitive.int, "an answer streamed as events reaches stdout as one line")
        run.send("""{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"boom"}}""")
        val refused = run.next()
        assertEquals(-32_000, refused["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        run.send("not json")
        assertEquals(-32_700, run.next()["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertTrue(run.finish(), "a reachable server is a bridge that ran")

        val posts = seen.filter { it.method == "POST" }
        val bearer = "Bearer SECRET-MCP-KEY"
        assertTrue(posts.all { it.authorization == bearer }, "every request carries the target's header")
        val afterInit = posts.drop(1)
        assertTrue(afterInit.all { it.session == SESSION_ID }, "the session the server gave rides every later request")
        assertTrue(afterInit.all { it.version == "2025-11-25" }, "the agreed version rides every later request")
        assertTrue(seen.any { it.method == "DELETE" && it.session == SESSION_ID }, "the session is ended at EOF")
        assertFalse(run.stdout().contains("SECRET-MCP-KEY"))
        assertFalse(errLines.any { "SECRET-MCP-KEY" in it }, "stderr never carries a header value")
    }

    @Test
    fun `the older http and sse transport posts to the endpoint the stream names and hears answers on the stream`() {
        val events = LinkedBlockingQueue<String>()
        server.createContext("/sse") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write("event: endpoint\ndata: /messages?session=7\n\n".toByteArray())
                body.flush()
                var event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS)
                while (event != null && event != END) {
                    body.write("event: message\ndata: $event\n\n".toByteArray())
                    body.flush()
                    event = events.poll(WAIT_SECONDS, TimeUnit.SECONDS)
                }
            }
        }
        server.createContext("/messages") { exchange ->
            val message = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            queries += exchange.requestURI.query
            message["id"]?.let { id -> events += """{"jsonrpc":"2.0","id":$id,"result":{}}""" }
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
        val target = McpStdioTarget(url("/sse"), emptyMap(), legacySse = true)
        val run = Bridged(McpLegacySse(target, HttpClient.newHttpClient(), Dispatchers.IO))

        run.send("""{"jsonrpc":"2.0","id":"a","method":"initialize","params":{}}""")
        assertEquals("a", run.next()["id"]!!.jsonPrimitive.content)
        run.send("""{"jsonrpc":"2.0","id":"b","method":"tools/list"}""")
        assertEquals("b", run.next()["id"]!!.jsonPrimitive.content)
        events += END
        assertTrue(run.finish())
        assertEquals(listOf("session=7", "session=7"), queries.toList(), "messages go to the endpoint the stream named")
    }

    @Test
    fun `a server that can't be reached fails the bridge instead of leaving the client waiting`() {
        server.createContext("/sse") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        val errLines = ConcurrentLinkedQueue<String>()
        val run = Bridged(
            McpLegacySse(McpStdioTarget(url("/sse"), emptyMap(), true), HttpClient.newHttpClient(), Dispatchers.IO),
            errLines,
        )
        assertFalse(run.finish(), "a stream that refuses is a server that failed to start")
        assertTrue(errLines.any { "401" in it })
    }

    private fun streamable(exchange: HttpExchange) {
        val headers = exchange.requestHeaders
        seen += Seen(
            exchange.requestMethod,
            headers.getFirst("Authorization"),
            headers.getFirst("Mcp-Session-Id"),
            headers.getFirst("MCP-Protocol-Version"),
        )
        when (exchange.requestMethod) {
            "POST" -> posted(exchange)
            "DELETE" -> exchange.sendResponseHeaders(200, -1)
            else -> exchange.sendResponseHeaders(405, -1)
        }
        exchange.close()
    }

    private fun posted(exchange: HttpExchange) {
        val message = Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
        val id = message["id"]
        when (message["method"]?.jsonPrimitive?.content) {
            "initialize" -> {
                exchange.responseHeaders.add("Mcp-Session-Id", SESSION_ID)
                // An indented answer, which the bridge must put on one line.
                val indented = "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": $id,\n" +
                    "  \"result\": {\"protocolVersion\": \"2025-11-25\"}\n}"
                reply(exchange, "application/json", indented)
            }
            "tools/list" -> {
                val data = """{"jsonrpc":"2.0","id":$id,"result":{"tools":[]}}"""
                val event = "event: message\ndata: $data\n\n"
                reply(exchange, "text/event-stream", event)
            }
            "tools/call" -> exchange.sendResponseHeaders(500, -1)
            else -> exchange.sendResponseHeaders(202, -1)
        }
    }

    private fun reply(exchange: HttpExchange, type: String, body: String) {
        exchange.responseHeaders.add("Content-Type", type)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun url(path: String): URI = URI("http://127.0.0.1:${server.address.port}$path")

    /** One request the fake server saw: its method and the headers the bridge must carry. */
    private data class Seen(val method: String, val authorization: String?, val session: String?, val version: String?)

    /** A bridge running on its own thread, fed lines and read a line at a time. */
    private class Bridged(
        transport: McpStdioTransport,
        errLines: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    ) {
        private val input = QueueReader()
        private val output = LineSink()
        private val bridge = McpStdioBridge("test", transport, Dispatchers.IO, TerminalOutput { errLines += it })
        private val running = Executors.newSingleThreadExecutor().submit<Boolean> {
            runBlocking { async(Dispatchers.IO) { bridge.run(BufferedReader(input), output) }.await() }
        }

        fun send(line: String) = input.offer(line + "\n")

        fun next(): JsonObject {
            val line = output.lines.poll(WAIT_SECONDS, TimeUnit.SECONDS)
            assertNotNull(line, "the bridge answered")
            return Json.parseToJsonElement(line!!).jsonObject
        }

        fun finish(): Boolean {
            input.end()
            return running.get(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        fun stdout(): String = output.all.toString()
    }

    /** stdin the test writes to; ends when [end] is called. */
    private class QueueReader : Reader() {
        private val chunks = LinkedBlockingQueue<String>()
        private var pending = ""

        fun offer(text: String) = chunks.offer(text)

        fun end() = chunks.offer(EOF)

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            if (pending.isEmpty()) pending = chunks.take()
            if (pending == EOF) return -1
            val count = minOf(length, pending.length)
            pending.toCharArray(buffer, offset, 0, count)
            pending = pending.substring(count)
            return count
        }

        override fun close() = Unit
    }

    /** stdout, split into the lines the bridge wrote. */
    private class LineSink : OutputStream() {
        val lines = LinkedBlockingQueue<String>()
        val all = StringBuilder()
        private val current = StringBuilder()

        override fun write(b: Int) {
            val char = b.toChar()
            synchronized(all) { all.append(char) }
            if (char == '\n') {
                lines += current.toString()
                current.setLength(0)
            } else {
                current.append(char)
            }
        }
    }
}

private const val SESSION_ID = "session-1"
private const val END = "END"
private const val EOF = "\u0000EOF"
private const val WAIT_SECONDS = 10L
