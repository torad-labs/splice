// NEW: Meta's Muse gateway as a head meets it, answering each request by the route it rides
// (x-meta-ai-gateway-session-id). A route with a generation stuck on it answers what Meta answered on Oct 26 from
// 02:37 CT: a 504 gateway_timeout with connection: close, which says nothing about the generation behind it. A route
// can also carry a generation that is still running: its request is streamed the start of an answer that never ends
// while the test runs, or whose connection breaks under it, and every later request on that route waits behind it
// until the gateway gives up. A route can also finish one response with no answer in it, and leave nothing running.
// Every other route streams the turn's answer.
//
// It speaks HTTP/1.1 over plain sockets, one request per connection, so a broken stream is what a network that drops a
// connection sends: a reset, never a clean end of the body.
package splice.app.provider

import splice.core.wire.HttpStatus
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** What every route that is not stuck answers. */
internal const val META_ANSWER = "Meta answered."

/** The start of the answer a route streams while Meta is still generating the rest. */
internal const val META_PARTIAL = "Meta is still"

private const val ROUTE_HEADER = "x-meta-ai-gateway-session-id"

// Connections the gateway queues before it accepts them; a test opens a handful at most.
private const val BACKLOG = 50

/** Meta's 504 body, verbatim from the Oct 26 trace. */
private const val GATEWAY_TIMEOUT_BODY =
    """{"error":{"code":"gateway_timeout","message":"The response stream did not start before the server """ +
        """timeout.","param":null,"type":"server_error"}}"""

private const val ITEM_ADDED = """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}"""
private const val PARTIAL_DELTA = """{"type":"response.output_text.delta","output_index":0,"delta":"$META_PARTIAL"}"""
private const val ANSWER_DELTA = """{"type":"response.output_text.delta","output_index":0,"delta":"$META_ANSWER"}"""
private const val ITEM_DONE = """{"type":"response.output_item.done","output_index":0}"""
private const val COMPLETED =
    """{"type":"response.completed","response":{"id":"resp_meta","status":"completed","output":[],""" +
        """"usage":{"input_tokens":3,"output_tokens":3}}}"""

internal class MetaGatewayUpstream {
    /** The route each request rode, in arrival order; null for a request that named none. */
    val routes: MutableList<String?> = CopyOnWriteArrayList()

    private val stuck = ConcurrentHashMap.newKeySet<String>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val breakingBeforeAnswer = ConcurrentHashMap.newKeySet<String>()
    private val breakingMidAnswer = ConcurrentHashMap.newKeySet<String>()
    private val empty = ConcurrentHashMap.newKeySet<String>()
    private val breakNow = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))

    /** Where a Muse provider points: the Responses API root, which it extends with /responses. */
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}/v1"

    init {
        pool.execute { acceptAll() }
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

    /** The next request on [route] starts a generation whose connection breaks before the head is sent any of the
     *  answer, while Meta goes on generating it. From then on the route is [stall]ed behind that generation. */
    fun breakBeforeAnswer(route: String) {
        breakingBeforeAnswer += route
    }

    /** The next request on [route] starts a generation that streams [META_PARTIAL] and holds there until [breakHeld]
     *  breaks its connection, while Meta goes on generating the rest. From then on the route is [stall]ed behind it. */
    fun breakMidAnswer(route: String) {
        breakingMidAnswer += route
    }

    /** The connection [breakMidAnswer] holds breaks now. */
    fun breakHeld() {
        breakNow.countDown()
    }

    /** Meta finishes the next request on [route] with no answer at all, and generates nothing more there. */
    fun answerEmptyOnce(route: String) {
        empty += route
    }

    fun stop() {
        stopped.countDown()
        server.close()
        pool.shutdownNow()
    }

    private fun acceptAll() {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                // The gateway is stopping.
                return
            }
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val route = readRequest(BufferedInputStream(socket.getInputStream()))
                routes += route
                answer(socket, route)
            }
        } catch (_: IOException) {
            // The head let go of the request; Meta goes on generating it all the same.
        } catch (_: InterruptedException) {
            // The gateway is stopping.
        }
    }

    /** The gateway answers by route, never by what was asked. */
    private fun answer(socket: Socket, route: String?) {
        val out = socket.getOutputStream()
        when {
            route == null -> stream(out)
            route in stuck -> gatewayTimeout(out)
            starting.remove(route) -> {
                stuck += route
                generating(out)
            }
            breakingBeforeAnswer.remove(route) -> {
                stuck += route
                sseHead(out)
                reset(socket)
            }
            breakingMidAnswer.remove(route) -> {
                stuck += route
                sseHead(out)
                send(out, ITEM_ADDED)
                send(out, PARTIAL_DELTA)
                breakNow.await()
                reset(socket)
            }
            empty.remove(route) -> {
                sseHead(out)
                send(out, COMPLETED)
                endChunks(out)
            }
            else -> stream(out)
        }
    }

    private fun gatewayTimeout(out: OutputStream) {
        val body = GATEWAY_TIMEOUT_BODY.toByteArray()
        val head = "HTTP/1.1 ${HttpStatus.GATEWAY_TIMEOUT} Gateway Timeout\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun stream(out: OutputStream) {
        sseHead(out)
        listOf(ITEM_ADDED, ANSWER_DELTA, ITEM_DONE, COMPLETED).forEach { event -> send(out, event) }
        endChunks(out)
    }

    /** The start of an answer, then a stream that stays open while Meta generates the rest, which it never finishes
     *  while the test runs. A head that lets go of the request closes the connection under it. */
    private fun generating(out: OutputStream) {
        sseHead(out)
        send(out, ITEM_ADDED)
        send(out, PARTIAL_DELTA)
        stopped.await()
    }

    /** The connection breaks the way a dropped network breaks it: a reset, with the chunked body unfinished. */
    private fun reset(socket: Socket) {
        socket.setSoLinger(true, 0)
        socket.close()
    }

    private fun sseHead(out: OutputStream) {
        val head = "HTTP/1.1 ${HttpStatus.OK} OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.flush()
    }

    private fun send(out: OutputStream, event: String) {
        val bytes = "data: $event\n\n".toByteArray()
        out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun endChunks(out: OutputStream) {
        out.write("0\r\n\r\n".toByteArray())
        out.flush()
    }

    /** Reads one request off [input], body included, and returns the route it rides, or null when it names none. */
    private fun readRequest(input: InputStream): String? {
        var route: String? = null
        var length = 0
        var chunked = false
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val name = line.substringBefore(':', "").trim()
            val value = line.substringAfter(':', "").trim()
            when {
                name.equals(ROUTE_HEADER, ignoreCase = true) -> route = value
                name.equals("Content-Length", ignoreCase = true) -> length = value.toInt()
                name.equals("Transfer-Encoding", ignoreCase = true) -> chunked = value.contains("chunked", true)
            }
        }
        if (chunked) skipChunks(input) else input.readNBytes(length)
        return route
    }

    private fun skipChunks(input: InputStream) {
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(radix = 16) ?: return
            input.readNBytes(size)
            readLine(input)
            if (size == 0) return
        }
    }

    /** One line without its line ending; an empty line at the end of the input. */
    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return line.toString()
            if (c != '\r'.code) line.append(c.toChar())
        }
    }
}
