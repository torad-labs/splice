// What the OpenAI route's tests stand on (docs/specs/server-mode.md, F4, openai-route): a scripted server in the
// provider's place that speaks OpenAI's chat API, a head that answers Claude Code's own routes, and server mode opened
// over both the way the daemon opens it (ServerRig). The chat dialect's part (what a request goes on as, and how its
// stream is read) is the daemon's to bring, so here it is stood in for by the plainest one that can be told apart on
// the wire.
package splice.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.topology.ServerLimits
import splice.core.util.LogSink
import splice.share.StreamEdit
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** A server that speaks OpenAI's chat API the way a test scripts it, and keeps what it was asked. */
internal class ScriptedBox(private val name: String) {
    /** One request as the server got it: its path and query, its headers by lowercased name, and its body. */
    data class Asked(val path: String, val headers: Map<String, String>, val body: String)

    val asked = CopyOnWriteArrayList<Asked>()

    /** The events a streamed completion answers with. */
    @Volatile var events: List<String> = listOf(WORD_EVENT, USAGE_EVENT, DONE_EVENT)

    /** The pause before each event after the first. */
    @Volatile var gapMs = 0L

    /** How many events go out before the server breaks the stream off; null sends them all and ends it whole. While
     *  it is set, an answer that is no stream breaks off halfway too. */
    @Volatile var cutAfter: Int? = null

    /** What a streamed completion waits for before it answers. */
    @Volatile var gate: CountDownLatch? = null

    /** The status a completion is refused with; null answers it. */
    @Volatile var refuses: Int? = null

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/v1") { exchange -> exchange.use(::answer) }
        start()
    }

    /** The server's API root, as a provider's `base_url` spells one. */
    val base: String get() = "http://127.0.0.1:${server.address.port}/v1"

    fun stop() {
        gate?.countDown()
        server.stop(0)
    }

    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.use { String(it.readBytes()) }
        val headers = exchange.requestHeaders.entries.associate { (key, values) -> key.lowercase() to values.first() }
        asked += Asked(exchange.requestURI.toString(), headers, body)
        val refused = refuses
        when {
            exchange.requestURI.path.endsWith("/models") -> whole(exchange, BOX_MODELS)
            refused != null -> whole(exchange, """{"error":{"message":"$name says $refused"}}""", refused)
            "\"stream\":true" in body -> stream(exchange)
            else -> whole(exchange, """{"choices":[{"message":{"content":"from $name"}}],$BOX_USAGE}""")
        }
    }

    private fun whole(exchange: HttpExchange, json: String, status: Int = 200) {
        val bytes = json.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        // An answer that is broken off declared all of itself, so closing it after half cuts the connection.
        exchange.responseBody.write(bytes, 0, if (cutAfter == null) bytes.size else bytes.size / 2)
    }

    private fun stream(exchange: HttpExchange) {
        gate?.await(GATE_SECONDS, TimeUnit.SECONDS)
        val cut = cutAfter
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        // A stream that is broken off declares more than it sends, so closing it cuts the connection mid-answer.
        exchange.sendResponseHeaders(200, if (cut == null) 0 else CUT_STREAM_LENGTH)
        events.take(cut ?: events.size).forEachIndexed { at, event ->
            if (at > 0) LockSupport.parkNanos(gapMs * NANOS_PER_MS)
            exchange.responseBody.write("$event\n\n".toByteArray())
            exchange.responseBody.flush()
        }
    }
}

/** A provider that speaks openai-chat, as the daemon describes one to the listener: its `extra_headers` say
 *  `x-box-team: blue`. A request goes on as it was sent, and the stream of one that asked for a stream is read by
 *  [UsageHidden]. */
internal class BoxServer(override val base: String) : ChatServer {
    override fun headers(): Map<String, String> = mapOf("Authorization" to "Bearer $BOX_KEY", "x-box-team" to "blue")

    override fun chat(sent: ByteArray): ChatAsk =
        ChatAsk(sent, UsageHidden().takeIf { "\"stream\":true" in String(sent) })
}

/** Stands in for the chat dialect's reading of a stream: the event that carries no choice is left out, and a stream
 *  the server ended without `[DONE]` is given it. */
internal class UsageHidden : StreamEdit {
    private var done = false

    override fun event(event: String): String? {
        if (event == DONE_EVENT) done = true
        return event.takeUnless { "\"choices\":[]" in it }
    }

    override fun ending(): String? = DONE_EVENT.takeUnless { done }
}

/** Server mode over one command, `claude-team`, whose head answers Claude Code's routes and whose provider is what
 *  [upstreams] says. The server's own lines go to [log]. */
internal class OpenAiRig(
    state: Path,
    upstreams: HeadUpstreams,
    limits: ServerLimits = ServerLimits(),
    log: LogSink = LogSink { },
) {
    private val rig = ServerRig(log = log, upstreams = upstreams)
    val port = rig.port
    private val http = HttpClient.newHttpClient()

    private val head = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1") { exchange ->
            exchange.use {
                it.requestBody.use { body -> body.readBytes() }
                val (status, answer) = when (it.requestURI.path) {
                    "/v1/models" -> 200 to HEAD_MODELS
                    "/v1/messages" -> 200 to """{"type":"message","content":[{"type":"text","text":"from the head"}]}"""
                    else -> 404 to HEAD_NO_ROUTE
                }
                it.responseHeaders.add("Content-Type", "application/json")
                it.sendResponseHeaders(status, answer.toByteArray().size.toLong())
                it.responseBody.write(answer.toByteArray())
            }
        }
        start()
    }

    private val mode = rig.open(state, listOf(StandInHead(head.address.port)), rig.config.copy(limits = limits))

    fun close() {
        rig.close()
        head.stop(0)
    }

    /** A key issued to [person]. */
    fun keyFor(person: String): String = rig.keyFor(person)

    /** Sets the upstream [person]'s turns prefer, as `splice server person set --upstream` does. */
    fun prefer(person: String, upstream: String) {
        runBlocking { mode.personApi.set(person, buildJsonObject { put("upstream", upstream) }) }
    }

    /** `/claude-team/v1/<path>` as an OpenAI-style client asks it, a POST when it has a [body]: its key, when it has
     *  one, as a bearer, and [extra], the headers of the caller's own. */
    private fun request(path: String, key: String?, body: String?, extra: ClientHeaders = emptyList()): HttpRequest {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/claude-team/v1/$path"))
            .method(if (body == null) "GET" else "POST", publisher)
            .header("Content-Type", "application/json")
        key?.let { request.header("Authorization", "Bearer $it") }
        extra.forEach { (name, value) -> request.header(name, value) }
        return request.build()
    }

    /** A chat completion asked with [body], and its whole answer. */
    fun chat(key: String?, body: String, query: String = "", extra: ClientHeaders = emptyList()): HttpResponse<String> =
        http.send(request("chat/completions$query", key, body, extra), HttpResponse.BodyHandlers.ofString())

    /** A chat completion sent without waiting for its answer. */
    fun chatLater(key: String, body: String): CompletableFuture<HttpResponse<String>> =
        http.sendAsync(request("chat/completions", key, body), HttpResponse.BodyHandlers.ofString())

    /** A streamed chat completion's `data:` lines, each with the milliseconds since its headers came. */
    fun stream(key: String, body: String, extra: ClientHeaders = emptyList()): List<Pair<Long, String>> {
        val answer = http.send(request("chat/completions", key, body, extra), HttpResponse.BodyHandlers.ofLines())
        val began = System.nanoTime()
        return answer.body().filter { it.startsWith("data:") }
            .map { (System.nanoTime() - began) / NANOS_PER_MS to it }.toList()
    }

    /** The models list, asked with [extra] as headers of the caller's own. */
    fun models(key: String?, vararg extra: Pair<String, String>): HttpResponse<String> =
        http.send(request("models", key, body = null, extra.toList()), HttpResponse.BodyHandlers.ofString())

    /** A turn on the Messages route, as Claude Code sends one. */
    fun turn(key: String): HttpResponse<String> =
        http.send(request("messages", key, """{"model":"m","messages":[]}"""), HttpResponse.BodyHandlers.ofString())

    /** Waits until [met], for the time a relay on loopback takes to notice what a test just did. */
    fun await(what: String, met: () -> Boolean) {
        val deadline = System.nanoTime() + AWAIT_SECONDS * NANOS_PER_MS * MS_PER_SECOND
        while (!met()) {
            check(System.nanoTime() < deadline) { "never happened: $what" }
            LockSupport.parkNanos(AWAIT_STEP_MS * NANOS_PER_MS)
        }
    }
}

/** Headers a client sends of its own, beside its key and its body's type. */
internal typealias ClientHeaders = List<Pair<String, String>>

internal const val BOX_KEY = "box-key-never-a-persons"
internal const val BOX_MODELS = """{"object":"list","data":[{"id":"team-model","object":"model","owned_by":"box"}]}"""
internal const val HEAD_MODELS = """{"data":[{"type":"model","id":"claude-team--team-model"}],"has_more":false}"""
internal const val HEAD_NO_ROUTE = """{"type":"error","error":{"type":"not_found_error","message":"head: no route"}}"""

internal const val CHAT = """{"model":"team-model","messages":[{"role":"user","content":"hi"}]}"""
internal const val STREAMED_CHAT =
    """{"model":"team-model","stream":true,"messages":[{"role":"user","content":"hi"}]}"""

// What every completion a scripted server answers whole says it used.
private const val BOX_USAGE = """"usage":{"prompt_tokens":7}"""

internal const val WORD_EVENT = """data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}"""
internal const val USAGE_EVENT = """data: {"choices":[],"usage":{"prompt_tokens":7}}"""
internal const val DONE_EVENT = "data: [DONE]"

private const val NANOS_PER_MS = 1_000_000L
private const val MS_PER_SECOND = 1_000L
private const val GATE_SECONDS = 20L
private const val AWAIT_SECONDS = 10L
private const val AWAIT_STEP_MS = 20L

// More than any scripted stream sends.
private const val CUT_STREAM_LENGTH = 1_000_000L
