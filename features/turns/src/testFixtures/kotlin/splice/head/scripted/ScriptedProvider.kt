// NEW: failure-handling slice 0, the harness. A fake provider that answers every API type splice speaks from a
// script (ReplyScript.kt), so a route test can put any answer a real backend gives, or fails to give, in front of a
// real head.
package splice.head.scripted

import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationCall
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.RoutingPipelineCall
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.netty.channel.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/** The only address the provider listens on. */
private const val LOOPBACK = "127.0.0.1"

/** What a request meets when the test scripted nothing: a server error that says so. */
private const val HTTP_INTERNAL_ERROR = 500

/** How long the provider gives its open exchanges to end when it stops. */
private const val STOP_TIMEOUT_MS = 1_000L

/** How a request reached the provider. */
public enum class Transport { HTTP, WEBSOCKET }

/** One request the provider received: a POST, or one frame a websocket round sent. [at] is the virtual time it
 *  arrived, measured from the clock's start. Header names are lowercase. */
public data class ProviderRequest(
    val transport: Transport,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val at: Duration,
)

/**
 * Where a reply's steps land: an HTTP answer before its status went out, its stream after, or a websocket round.
 * Each answers false when the reply ends at that step.
 */
internal interface ReplyWire {
    fun status(code: Int): Boolean

    fun header(name: String, value: String): Boolean

    suspend fun body(json: String): Boolean

    suspend fun event(data: String, name: String?): Boolean

    suspend fun keepalive(): Boolean

    suspend fun delay(by: Duration): Boolean

    suspend fun hold(): Boolean

    suspend fun cut(): Boolean

    suspend fun close(code: Int, reason: String): Boolean
}

/** How a stream or a websocket round plays its steps: in order, until one ends the reply. */
private object Playback {
    suspend fun play(steps: List<ReplyStep>, wire: ReplyWire) {
        for (step in steps) {
            if (!step.playOn(wire)) break
        }
    }
}

/** What a request meets when nothing was scripted. */
private val unscripted = ScriptedReply(
    listOf(
        ReplyStep.Status(HTTP_INTERNAL_ERROR),
        ReplyStep.Body("""{"error":{"type":"api_error","message":"the scripted provider has no reply scripted"}}"""),
    ),
)

/** The scripted replies: each request takes the next, and the last answers every request after it. */
private class ReplyQueue {
    @Volatile private var replies: List<ScriptedReply> = emptyList()
    private val taken = AtomicInteger()

    fun script(next: List<ScriptedReply>) {
        replies = next
        taken.set(0)
    }

    fun next(): ScriptedReply {
        val now = replies
        return now.getOrNull(taken.getAndIncrement()) ?: now.lastOrNull() ?: unscripted
    }
}

/** The TCP connection under one exchange, so a reply can drop it without ending the HTTP or websocket exchange. */
private class NettyLink(call: ApplicationCall) {
    private val channel: Channel = nettyCall(call).context.channel()

    /** Completes when the connection closed, from either end. */
    val hangup: CompletableDeferred<Unit> = CompletableDeferred()

    init {
        channel.closeFuture().addListener { hangup.complete(Unit) }
    }

    /** Closes the connection on its event loop, after what was flushed before, and returns once it is closed. */
    suspend fun drop() {
        channel.eventLoop().execute { channel.close() }
        hangup.await()
    }

    private fun nettyCall(call: ApplicationCall): NettyApplicationCall {
        val engineCall = when (call) {
            is RoutingCall -> call.pipelineCall.engineCall
            is RoutingPipelineCall -> call.engineCall
            else -> call
        }
        return checkNotNull(engineCall as? NettyApplicationCall) { "the scripted provider runs on Netty" }
    }
}

/** A reply's time: delays add up from the request's [arrival], and a hold lasts until release or hang-up. */
private class ReplyTiming(
    private val time: VirtualTime,
    private val arrival: Duration,
    private val released: Deferred<Unit>,
    private val hangup: Deferred<Unit>,
) {
    private var offset = Duration.ZERO

    /** True when the clock got there, false when it closed first, which ends the reply. */
    suspend fun delay(by: Duration): Boolean {
        offset += by
        return time.sleepUntil(arrival + offset)
    }

    /** True when the test released the hold, false when the head hung up first. */
    suspend fun hold(): Boolean = select {
        released.onAwait { true }
        hangup.onAwait { false }
    }
}

/** An HTTP answer before its status went out: the status and headers gather, and a body, a close or a cut ends it. */
private class HttpPrelude(
    private val call: ApplicationCall,
    private val timing: ReplyTiming,
    private val link: NettyLink,
) : ReplyWire {
    private var status = HttpStatusCode.OK
    private val headers = mutableListOf<Pair<String, String>>()

    override fun status(code: Int): Boolean {
        status = HttpStatusCode.fromValue(code)
        return true
    }

    override fun header(name: String, value: String): Boolean {
        headers += name to value
        return true
    }

    override suspend fun body(json: String): Boolean {
        sendHeaders()
        call.respondText(json, ContentType.Application.Json, status)
        return false
    }

    override suspend fun event(data: String, name: String?): Boolean = error("an event opens the stream")

    override suspend fun keepalive(): Boolean = error("a keepalive opens the stream")

    override suspend fun delay(by: Duration): Boolean = timing.delay(by)

    override suspend fun hold(): Boolean = timing.hold()

    override suspend fun cut(): Boolean {
        link.drop()
        return false
    }

    override suspend fun close(code: Int, reason: String): Boolean {
        respondEmpty()
        return false
    }

    /** The status and headers with no body, for a reply that ends before any. */
    suspend fun respondEmpty() {
        sendHeaders()
        call.respond(status)
    }

    /** The status and headers, then a server-sent event stream that [steps] write. */
    suspend fun respondStream(steps: List<ReplyStep>) {
        sendHeaders()
        call.respondBytesWriter(ContentType.Text.EventStream, status) {
            Playback.play(steps, HttpStream(this, timing, link))
        }
    }

    private fun sendHeaders() {
        headers.forEach { (name, value) -> call.response.headers.append(name, value, safeOnly = false) }
    }
}

/** An HTTP answer's stream: each event and keepalive is one server-sent frame, flushed as it is written. */
private class HttpStream(
    private val out: ByteWriteChannel,
    private val timing: ReplyTiming,
    private val link: NettyLink,
) : ReplyWire {
    override fun status(code: Int): Boolean = true

    override fun header(name: String, value: String): Boolean = true

    override suspend fun body(json: String): Boolean = write(json)

    override suspend fun event(data: String, name: String?): Boolean =
        write(name?.let { "event: $it\n" }.orEmpty() + "data: $data\n\n")

    override suspend fun keepalive(): Boolean = write(": keepalive\n\n")

    override suspend fun delay(by: Duration): Boolean = timing.delay(by)

    override suspend fun hold(): Boolean = timing.hold()

    override suspend fun cut(): Boolean {
        out.flush()
        link.drop()
        return false
    }

    override suspend fun close(code: Int, reason: String): Boolean = false

    private suspend fun write(text: String): Boolean {
        out.writeStringUtf8(text)
        out.flush()
        return true
    }
}

/** A websocket round: each event is a text frame, a keepalive a ping, and a close a close frame. */
private class SocketRound(
    private val session: DefaultWebSocketServerSession,
    private val timing: ReplyTiming,
    private val link: NettyLink,
) : ReplyWire {
    override fun status(code: Int): Boolean = true

    override fun header(name: String, value: String): Boolean = true

    override suspend fun body(json: String): Boolean {
        session.send(Frame.Text(json))
        return false
    }

    override suspend fun event(data: String, name: String?): Boolean {
        session.send(Frame.Text(data))
        return true
    }

    override suspend fun keepalive(): Boolean {
        session.send(Frame.Ping(ByteArray(0)))
        return true
    }

    override suspend fun delay(by: Duration): Boolean = timing.delay(by)

    override suspend fun hold(): Boolean = timing.hold()

    override suspend fun cut(): Boolean {
        session.flush()
        link.drop()
        return false
    }

    override suspend fun close(code: Int, reason: String): Boolean {
        session.close(CloseReason(code.toShort(), reason))
        return false
    }
}

/**
 * A provider on 127.0.0.1 that answers from a script: each request takes the next scripted reply, and the last
 * reply answers every request after it, so a head's retries meet the same answer. It answers a POST on any path
 * (Anthropic's /v1/messages, Chat's /chat/completions, Responses' /responses) and a websocket round on any path,
 * and it logs every request it received. A websocket stays open across rounds, as a real backend's does, and each
 * text frame the head sends is one request.
 */
public class ScriptedProvider(private val time: VirtualTime) {
    private val received = CopyOnWriteArrayList<ProviderRequest>()
    private val logged = MutableStateFlow(0)
    private val replies = ReplyQueue()
    private val released = CompletableDeferred<Unit>()
    private val lifecycle = Mutex()

    @Volatile private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @Volatile private var port = 0

    /** Where a head reaches this provider. */
    public val baseUrl: String
        get() {
            check(port > 0) { "start the scripted provider before asking where it listens" }
            return "http://$LOOPBACK:$port"
        }

    /** Every request received so far, in arrival order. */
    public val requests: List<ProviderRequest> get() = received.toList()

    /** Replaces the script: the replies in order, the last one repeating. */
    public fun script(vararg replies: ScriptedReply) {
        this.replies.script(replies.toList())
    }

    /** Returns the log once it holds at least [count] requests. */
    public suspend fun awaitRequests(count: Int): List<ProviderRequest> {
        logged.first { it >= count }
        return requests
    }

    /** Ends every [ReplyStep.Hold], now and later. */
    public fun release() {
        released.complete(Unit)
    }

    /** Starts listening; a second call does nothing. */
    public suspend fun start(): Unit = lifecycle.withLock {
        if (server != null) return@withLock
        val engine = embeddedServer(Netty, port = 0, host = LOOPBACK) {
            install(WebSockets)
            routing {
                post("{...}") { answerPost(call) }
                webSocket("{...}") { answerSocket(this) }
            }
        }
        engine.start(wait = false)
        port = engine.engine.resolvedConnectors().single().port
        server = engine
    }

    /** Stops listening. */
    public suspend fun stop(): Unit = lifecycle.withLock {
        server?.stop(0, STOP_TIMEOUT_MS)
        server = null
        port = 0
    }

    private suspend fun answerPost(call: ApplicationCall) {
        val (reply, arrival) = record(Transport.HTTP, call.request.path(), call.request.headers, call.receiveText())
        val link = NettyLink(call)
        val prelude = HttpPrelude(call, ReplyTiming(time, arrival, released, link.hangup), link)
        val ended = !reply.prelude().all { step -> step.playOn(prelude) }
        val stream = reply.stream()
        when {
            ended -> Unit
            stream.isEmpty() -> prelude.respondEmpty()
            else -> prelude.respondStream(stream)
        }
    }

    private suspend fun answerSocket(session: DefaultWebSocketServerSession) {
        val link = NettyLink(session.call)
        val path = session.call.request.path()
        val headers = session.call.request.headers
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val (reply, arrival) = record(Transport.WEBSOCKET, path, headers, frame.readText())
            val timing = ReplyTiming(time, arrival, released, link.hangup)
            Playback.play(reply.steps(), SocketRound(session, timing, link))
        }
    }

    private fun record(
        transport: Transport,
        path: String,
        headers: Headers,
        body: String,
    ): Pair<ScriptedReply, Duration> {
        val arrival = time.passed()
        val named = headers.entries().associate { (name, values) -> name.lowercase() to values.joinToString(", ") }
        received += ProviderRequest(transport, path, named, body, arrival)
        logged.value = received.size
        return replies.next() to arrival
    }
}
