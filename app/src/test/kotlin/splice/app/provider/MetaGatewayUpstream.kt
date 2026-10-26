// NEW: Meta's Muse gateway as a head meets it, answering each request by the route it rides
// (x-meta-ai-gateway-session-id). A route with a generation stuck on it answers what Meta answered on Oct 26 from
// 02:37 CT: a 504 gateway_timeout with connection: close, which says nothing about the generation behind it. A route
// can also carry a generation that is still running: its request is streamed the start of an answer that never ends
// while the test runs, and every later request on that route waits behind it until the gateway gives up. Every other
// route streams the turn's answer.
package splice.app.provider

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import splice.core.wire.HttpStatus
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** What every route that is not stuck answers. */
internal const val META_ANSWER = "Meta answered."

/** The start of the answer a route streams while Meta is still generating the rest. */
internal const val META_PARTIAL = "Meta is still"

/** Meta's 504 body, verbatim from the Oct 26 trace. */
private const val GATEWAY_TIMEOUT_BODY =
    """{"error":{"code":"gateway_timeout","message":"The response stream did not start before the server """ +
        """timeout.","param":null,"type":"server_error"}}"""

private const val ITEM_ADDED = """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}"""

internal class MetaGatewayUpstream {
    /** The route each request rode, in arrival order; null for a request that named none. */
    val routes: MutableList<String?> = CopyOnWriteArrayList()

    private val stuck = ConcurrentHashMap.newKeySet<String>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val stopped = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** Where a Muse provider points: the Responses API root, which it extends with /responses. */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    init {
        server.executor = pool
        server.createContext("/v1/responses") { exchange -> answer(exchange) }
        server.start()
    }

    /** A generation is running on [route] and has not started its stream, so every request that rides it waits behind
     *  that generation until the gateway gives up on it. */
    fun stall(route: String) {
        stuck += route
    }

    /** The next request on [route] starts a generation that is still running when its request's connection is let go:
     *  it is streamed [META_PARTIAL], and then nothing, for as long as the test runs. From then on the route is
     *  [stall]ed behind that generation. */
    fun generateWithoutEnd(route: String) {
        starting += route
    }

    fun stop() {
        stopped.countDown()
        server.stop(0)
        pool.shutdownNow()
    }

    private fun answer(exchange: HttpExchange) {
        // Closing drains the request body; the gateway answers by route, never by what was asked.
        exchange.requestBody.close()
        val route = exchange.requestHeaders.getFirst("x-meta-ai-gateway-session-id")
        routes += route
        when {
            route == null -> stream(exchange)
            route in stuck -> gatewayTimeout(exchange)
            starting.remove(route) -> {
                stuck += route
                generating(exchange)
            }
            else -> stream(exchange)
        }
    }

    private fun gatewayTimeout(exchange: HttpExchange) {
        val body = GATEWAY_TIMEOUT_BODY.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("Connection", "close")
        exchange.sendResponseHeaders(HttpStatus.GATEWAY_TIMEOUT, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun stream(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(HttpStatus.OK, 0)
        exchange.responseBody.use { out ->
            listOf(
                ITEM_ADDED,
                """{"type":"response.output_text.delta","output_index":0,"delta":"$META_ANSWER"}""",
                """{"type":"response.output_item.done","output_index":0}""",
                """{"type":"response.completed","response":{"id":"resp_meta","status":"completed","output":[],""" +
                    """"usage":{"input_tokens":3,"output_tokens":3}}}""",
            ).forEach { event -> send(out, event) }
        }
    }

    /** The start of an answer, then a stream that stays open while Meta generates the rest, which it never finishes
     *  while the test runs. A head that lets go of the request closes the connection under it. */
    private fun generating(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(HttpStatus.OK, 0)
        val out = exchange.responseBody
        try {
            send(out, ITEM_ADDED)
            send(out, """{"type":"response.output_text.delta","output_index":0,"delta":"$META_PARTIAL"}""")
            stopped.await()
        } catch (_: IOException) {
            // The head let go of the request; Meta goes on generating it all the same.
        } catch (_: InterruptedException) {
            // The gateway is stopping.
        }
    }

    private fun send(out: OutputStream, event: String) {
        out.write("data: $event\n\n".toByteArray())
        out.flush()
    }
}
