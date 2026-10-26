// NEW: Meta's Muse gateway as a head meets it, answering each request by the route it rides
// (x-meta-ai-gateway-session-id), and recording the prompt_cache_key it carries, which Meta routes by too and which
// moves with the route. A route with a generation stuck on it answers what Meta answered on Oct 26 from
// 02:37 CT: a 504 gateway_timeout with connection: close, which says nothing about the generation behind it. A route
// can also carry a generation that is still running: its request is streamed the start of an answer that never ends
// while the test runs, or whose connection breaks under it, and every later request on that route waits behind it
// until the gateway gives up. A route can also finish one response with no answer in it, and leave nothing running.
// Every other route streams the turn's answer. Meta keeps a conversation's prompt cache on the route it rides, so the
// first answer on a route reads none of its input from the cache, and every later one reads most of it. Meta's address
// can also stop taking connections for a moment, and Meta can take minutes to answer, which MetaWaits plays.
//
// It speaks HTTP/1.1 over plain sockets, one request per connection, so a broken stream is what a network that drops a
// connection sends: a reset, never a clean end of the body.
package splice.app.provider

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import splice.core.testing.TestPorts
import splice.core.util.ElapsedClock
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** What every route that is not stuck answers. */
internal const val META_ANSWER = "Meta answered."

/** The start of the answer a route streams while Meta is still generating the rest. */
internal const val META_PARTIAL = "Meta is still"

private const val ROUTE_HEADER = "x-meta-ai-gateway-session-id"
private const val CACHE_KEY_FIELD = "prompt_cache_key"

// Connections the gateway queues before it accepts them; a test opens a handful at most.
private const val BACKLOG = 50

// The input every request bills, and how much of it Meta reads from the cache of a route that has answered before.
private const val INPUT_TOKENS = 1_000
private const val CACHED_TOKENS = 900

/** Meta's 504 body, verbatim from the Oct 26 trace. */
private const val GATEWAY_TIMEOUT_BODY =
    """{"error":{"code":"gateway_timeout","message":"The response stream did not start before the server """ +
        """timeout.","param":null,"type":"server_error"}}"""

private const val ITEM_ADDED = """{"type":"response.output_item.added","output_index":0,"item":{"type":"message"}}"""
private const val PARTIAL_DELTA = """{"type":"response.output_text.delta","output_index":0,"delta":"$META_PARTIAL"}"""
private const val ANSWER_DELTA = """{"type":"response.output_text.delta","output_index":0,"delta":"$META_ANSWER"}"""
private const val ITEM_DONE = """{"type":"response.output_item.done","output_index":0}"""

/** One request as the gateway read it: the [route] it rode and the [cacheKey] its body carried, each null when the
 *  request named none. */
internal data class MetaRequest(val route: String?, val cacheKey: String?)

internal class MetaGatewayUpstream {
    /** Every request the gateway read, in arrival order. */
    val requests: MutableList<MetaRequest> = CopyOnWriteArrayList()

    /** How long Meta takes, as the head's clock reads it. */
    val waits = MetaWaits()

    private val stuck = ConcurrentHashMap.newKeySet<String>()
    private val starting = ConcurrentHashMap.newKeySet<String>()
    private val breakingBeforeAnswer = ConcurrentHashMap.newKeySet<String>()
    private val breakingMidAnswer = ConcurrentHashMap.newKeySet<String>()
    private val empty = ConcurrentHashMap.newKeySet<String>()
    private val cached = ConcurrentHashMap.newKeySet<String>()
    private val breakNow = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val pool = Executors.newCachedThreadPool()

    // A port nothing else is handed while the gateway stops listening on it (kt-tests-bind-port-zero).
    private val port = TestPorts.reserve()
    private val listening = Any()
    private var server = bind()

    /** Where a Muse provider points: the Responses API root, which it extends with /responses. */
    val baseUrl: String get() = "http://127.0.0.1:$port/v1"

    init {
        val first = server
        pool.execute { acceptAll(first) }
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

    /** Nothing listens at Meta's address from now on, so a connection to it is refused before a byte of its request is
     *  written, until [listen]. */
    fun refuseConnections() {
        synchronized(listening) { server.close() }
    }

    /** Meta's address listens again, when it had stopped. */
    fun listen() {
        synchronized(listening) {
            if (!server.isClosed || stopped.count == 0L) return
            val again = bind()
            server = again
            pool.execute { acceptAll(again) }
        }
    }

    fun stop() {
        synchronized(listening) {
            stopped.countDown()
            server.close()
        }
        pool.shutdownNow()
    }

    /** A listener on the gateway's one port. A listener that stopped leaves the connections it closed in TIME_WAIT on
     *  that port, so the next one binds beside them. */
    private fun bind(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), BACKLOG)
    }

    private fun acceptAll(listener: ServerSocket) {
        while (true) {
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                // The gateway stopped listening.
                return
            }
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val request = readRequest(BufferedInputStream(socket.getInputStream()))
                requests += request
                answer(socket, request.route)
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
            route == null -> stream(out, route)
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
                send(out, completed(route))
                endChunks(out)
            }
            else -> stream(out, route)
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

    private fun stream(out: OutputStream, route: String?) {
        sseHead(out)
        listOf(ITEM_ADDED, ANSWER_DELTA, ITEM_DONE, completed(route)).forEach { event -> send(out, event) }
        endChunks(out)
    }

    /** The response's last event, billing what Meta read from the cache of [route]: none the first time the route
     *  answers, when Meta writes the cache there, and most of the input on every answer after. */
    private fun completed(route: String?): String {
        val read = if (route != null && !cached.add(route)) CACHED_TOKENS else 0
        return """{"type":"response.completed","response":{"id":"resp_meta","status":"completed","output":[],""" +
            """"usage":{"input_tokens":$INPUT_TOKENS,"input_tokens_details":{"cached_tokens":$read},""" +
            """"output_tokens":3}}}"""
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

    /** Reads one request off [input], body included: the route it rides and the cache key its body carries. */
    private fun readRequest(input: InputStream): MetaRequest {
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
        val body = if (chunked) readChunks(input) else input.readNBytes(length)
        val json = JsonScalars.objectOrNull(Json, body.decodeToString())
        return MetaRequest(route, JsonScalars.str(json, CACHE_KEY_FIELD))
    }

    /** A chunked body's chunks, joined, up to the empty one that ends it. */
    private fun readChunks(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        do {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull(radix = 16) ?: 0
            body.write(input.readNBytes(size))
            readLine(input)
        } while (size > 0)
        return body.toByteArray()
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

/**
 * How long Meta takes, on the clock the head times Meta's answers by. A test cannot keep a socket waiting for a minute,
 * so a wait of Meta's is a step of that clock, taken in the head's own transport at the point the head would have
 * waited: [holdNextHeaders] steps it after the next request's last byte and before its headers are read, and
 * [slowNextWrite] before that request's last byte. The head installs it as its innermost network interceptor, so the
 * body it wraps is the one the connection writes, around every mark the head itself makes of the write.
 */
internal class MetaWaits : Interceptor {
    private val now = AtomicLong()
    private val holdNext = AtomicLong()
    private val slowWriteNext = AtomicLong()

    /** The clock the head times Meta by. */
    val clock: ElapsedClock = ElapsedClock { now.get() }

    /** Meta holds the next request's headers for [ms] after it has all of the request. */
    fun holdNextHeaders(ms: Long) {
        holdNext.set(ms)
    }

    /** The next request takes [ms] to write, and Meta answers it the moment it has all of it. */
    fun slowNextWrite(ms: Long) {
        slowWriteNext.set(ms)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val slow = slowWriteNext.getAndSet(0)
        val hold = holdNext.getAndSet(0)
        if (slow == 0L && hold == 0L) return chain.proceed(request)
        val timed = object : RequestBody() {
            override fun contentType(): MediaType? = body.contentType()

            override fun contentLength(): Long = body.contentLength()

            override fun writeTo(sink: BufferedSink) {
                now.addAndGet(slow)
                body.writeTo(sink)
                now.addAndGet(hold)
            }
        }
        return chain.proceed(request.newBuilder().method(request.method, timed).build())
    }
}
