package splice.app.mcp.stdio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.terminal.TerminalOutput
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.StringReader
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpStdioBridgeEdgesTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `an SSE event the stream cut off is not dispatched, and an empty event name is a message`() {
        val events = McpSseEvents(BufferedReader(StringReader("event:\ndata: {\"a\":1}\n\ndata: {\"cut\":true}")))
        assertEquals(McpSseEvent("message", "{\"a\":1}"), events.next())
        assertNull(events.next(), "an event with no closing blank line never reaches the client")
    }

    @Test
    fun `a request the server takes with an empty body is answered with an error instead of left waiting`() {
        server.createContext("/mcp/quiet") { exchange ->
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
        val target = McpStdioTarget(URI("http://127.0.0.1:${server.address.port}/mcp/quiet"), emptyMap())
        val stdout = run(McpStreamableHttp(target, HttpClient.newHttpClient(), Dispatchers.IO), REQUEST)
        assertEquals(-32_000, error(stdout.single()))
    }

    @Test
    fun `an answer streamed with an upper-case event-stream type still reaches the client`() {
        server.createContext("/mcp/loud") { exchange ->
            exchange.responseHeaders.add("Content-Type", "Text/Event-Stream; charset=utf-8")
            val bytes = "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}\n\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val target = McpStdioTarget(URI("http://127.0.0.1:${server.address.port}/mcp/loud"), emptyMap())
        val stdout = run(McpStreamableHttp(target, HttpClient.newHttpClient(), Dispatchers.IO), REQUEST)
        assertEquals(7, Json.parseToJsonElement(stdout.single()).jsonObject["id"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an older-transport endpoint that is malformed or at another origin is a server that did not open`() {
        val named = ConcurrentLinkedQueue(listOf("http://[malformed", "http://elsewhere.invalid/messages"))
        server.createContext("/sse") { exchange -> endpointStream(exchange, named.poll()) }
        server.start()
        listOf("malformed", "another origin").forEach { case ->
            val errLines = ConcurrentLinkedQueue<String>()
            val target = McpStdioTarget(URI("http://127.0.0.1:${server.address.port}/sse"), emptyMap(), true)
            val transport = McpLegacySse(target, HttpClient.newHttpClient(), Dispatchers.IO)
            val bridge = McpStdioBridge("t", transport, Dispatchers.IO, TerminalOutput { errLines += it })
            val reached = runBlocking { bridge.run(BufferedReader(StringReader(REQUEST)), ByteArrayOutputStream()) }
            assertFalse(reached, case)
            assertTrue(errLines.any { "no endpoint at its own address" in it }, "$case: $errLines")
        }
    }

    @Test
    fun `an endpoint named with an upper-case scheme and host is the server's own`() {
        server.createContext("/sse") { exchange ->
            endpointStream(exchange, "HTTP://127.0.0.1:${server.address.port}/messages")
        }
        server.createContext("/messages") { exchange ->
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        server.start()
        val target = McpStdioTarget(URI("http://127.0.0.1:${server.address.port}/sse"), emptyMap(), true)
        val transport = McpLegacySse(target, HttpClient.newHttpClient(), Dispatchers.IO)
        val bridge = McpStdioBridge("t", transport, Dispatchers.IO, TerminalOutput {})
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n"
        assertTrue(runBlocking { bridge.run(BufferedReader(StringReader(notification)), ByteArrayOutputStream()) })
    }

    @Test
    fun `a request that can't be built is answered with an error that carries no header value`() {
        val errLines = ConcurrentLinkedQueue<String>()
        val transport = Fake(answer = { throw IllegalArgumentException("invalid header value: SECRET-VALUE") })
        val stdout = run(transport, REQUEST, errLines)
        assertEquals(-32_000, error(stdout.single()))
        assertFalse(stdout.single().contains("SECRET-VALUE"))
        assertFalse(errLines.any { "SECRET-VALUE" in it })
        assertEquals(1, transport.closes.get(), "the session is still ended")
    }

    @Test
    fun `a bridge cancelled while it runs still ends the session`() {
        val transport = Fake()
        val started = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int {
                started.countDown()
                cancelled.await(WAIT, TimeUnit.SECONDS)
                return -1
            }
        }
        val bridge = McpStdioBridge("t", transport, Dispatchers.IO, TerminalOutput {})
        assertThrows(CancellationException::class.java) {
            runBlocking {
                val running = async(Dispatchers.IO) { bridge.run(input.bufferedReader(), ByteArrayOutputStream()) }
                assertTrue(started.await(WAIT, TimeUnit.SECONDS))
                running.cancel()
                cancelled.countDown()
                withTimeout(WAIT * 1_000) { running.await() }
            }
        }
        assertEquals(1, transport.closes.get(), "the finally block ended the session")
    }

    private val cancelled = CountDownLatch(1)

    private fun run(
        transport: McpStdioTransport,
        input: String,
        errLines: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    ): List<String> {
        val stdout = ByteArrayOutputStream()
        val bridge = McpStdioBridge("t", transport, Dispatchers.IO, TerminalOutput { errLines += it })
        runBlocking { bridge.run(BufferedReader(StringReader(input)), stdout) }
        return stdout.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    private fun error(line: String): Int = Json.parseToJsonElement(line).jsonObject["error"]!!.jsonObject["code"]!!
        .jsonPrimitive.int

    private fun endpointStream(exchange: HttpExchange, endpoint: String?) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { body ->
            body.write("event: endpoint\ndata: $endpoint\n\n".toByteArray())
            body.flush()
        }
    }

    /** A transport whose send does what the test says, counting the sessions ended. */
    private class Fake(
        private val answer: (JsonObject) -> McpStdioSent = { McpStdioSent.Delivered },
    ) : McpStdioTransport {
        val closes = AtomicInteger()

        override suspend fun open(out: TerminalOutput): String? = null

        override suspend fun send(message: JsonObject, out: TerminalOutput): McpStdioSent = answer(message)

        override suspend fun listen(out: TerminalOutput) = Unit

        override suspend fun close() {
            closes.incrementAndGet()
        }
    }
}

private const val REQUEST = """{"jsonrpc":"2.0","id":7,"method":"tools/list"}""" + "\n"
private const val WAIT = 10L
