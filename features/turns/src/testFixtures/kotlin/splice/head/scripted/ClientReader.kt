// NEW: failure-handling slice 0, the harness. Reads a head's answer the way Claude Code receives it: the status,
// the headers, the server-sent events and splice's signed notice blocks.
package splice.head.scripted

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import splice.core.client.SESSION_HEADER
import splice.core.turn.SpliceNotice
import java.util.UUID
import kotlin.time.Duration

/** The bearer token HeadDepsFixture gives every head's callers. */
private const val INFERENCE_TOKEN = "test-inference-token"

/** The Claude Code release this reader stands in for, as its user agent names it. */
private const val CLIENT_AGENT = "claude-cli/2.1.310 (external, cli)"

/** The Messages API version Claude Code sends. */
private const val ANTHROPIC_VERSION = "2023-06-01"

private const val HTTP_OK = 200
private const val HTTP_MULTIPLE_CHOICES = 300
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500
private const val HTTP_STATUS_END = 600

private const val ERROR = "error"
private const val OVERLOADED = "overloaded_error"
private const val INVALID_REQUEST = "invalid_request_error"
private const val THINKING = "thinking"

/** How much of the text and the body [ClientAnswer.describe] shows. */
private const val DESCRIBE_CHARS = 300

/** The answer budget a request asks for when a test names none. */
private const val DEFAULT_MAX_TOKENS = 1024

/** What the client sends: one user message, streamed unless [stream] is false, in session [session]. [extra] adds
 *  top-level body fields, such as an output format or a tool choice, and [bearer] replaces the head's own token.
 *  [headers] are sent besides the ones Claude Code always sends, such as the client timeout a launched session names. */
public data class ClientRequest(
    val text: String = "Say something.",
    val stream: Boolean = true,
    val session: String = UUID.randomUUID().toString(),
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val extra: JsonObject = JsonObject(emptyMap()),
    val bearer: String = INFERENCE_TOKEN,
    val headers: Map<String, String> = emptyMap(),
)

/** One server-sent event as the client parsed it. [name] is null for a data-only event. */
public data class SseEvent(val name: String?, val data: String)

/** The error an answer ended with: its Anthropic error type and its message. */
public data class ClientError(val type: String, val message: String)

/**
 * The family an answer puts the client in, by how Claude Code acts on it:
 * R re-sends; P stops for good; T compacts, which is P plus the words "prompt is too long"; H stops on a limit
 * whose reset is too far; L waits out a limit and re-sends.
 */
public enum class Family { R, P, T, H, L }

/** The JSON reads an answer needs, each null where the shape is not there. */
private object WireJson {
    fun parse(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject

    fun obj(json: JsonObject?, key: String): JsonObject? = json?.get(key) as? JsonObject

    fun str(json: JsonObject?, key: String): String? = (json?.get(key) as? JsonPrimitive)?.contentOrNull

    fun type(event: SseEvent): String? = event.name ?: str(parse(event.data), "type")
}

/** One content block as the client assembled it from its start, its deltas and its signature. [startedAt] is the
 *  index of the event that opened it. */
private class ClientBlock(val type: String, val startedAt: Int) {
    private val written = StringBuilder()
    private var signature: String? = null
    private var deltas = 0

    val text: String get() = written.toString()

    /** Splice's own wait notice: a thinking block signed with [SpliceNotice.SIGNATURE]. */
    val isNotice: Boolean get() = type == THINKING && signature == SpliceNotice.SIGNATURE

    /** The model's content: a delta in a block that is not a notice. A thinking block still unsigned counts. */
    val isContent: Boolean get() = deltas > 0 && !isNotice

    fun add(delta: JsonObject) {
        when (WireJson.str(delta, "type")) {
            "signature_delta" -> signature = WireJson.str(delta, "signature")
            "text_delta" -> grow(WireJson.str(delta, "text"))
            "thinking_delta" -> grow(WireJson.str(delta, THINKING))
            else -> deltas++
        }
    }

    private fun grow(part: String?) {
        written.append(part.orEmpty())
        deltas++
    }
}

/** Everything one answer delivered to the client, to its end or to where the connection dropped. */
public data class ClientAnswer(
    val status: Int,
    /** Response headers, names lowercase. */
    val headers: Map<String, List<String>>,
    /** The server-sent events, in order. Empty for a JSON answer. */
    val events: List<SseEvent>,
    /** The whole body of a JSON answer. Empty for a stream. */
    val body: String,
    /** True when the connection dropped before the answer ended. */
    val cut: Boolean,
    /** What the read failed with when the connection dropped, for [describe]. */
    val failure: String? = null,
    /** When each of [events] arrived, on the clock the reader was given: the scripted head's virtual clock, the one
     *  [ProviderRequest.at] is read on, so a test can tell what the client held when the provider was asked. */
    val arrivals: List<Duration> = emptyList(),
) {
    /** The first value of header [name], any case. */
    public fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** The x-should-retry header, when the answer carried one. */
    public val shouldRetry: String? get() = header("x-should-retry")

    /** The error this answer ended with: an in-band error event, or the error body of an HTTP failure. */
    public val error: ClientError?
        get() = events.firstOrNull { WireJson.type(it) == ERROR }?.let { errorIn(it.data) }
            ?: errorIn(body)

    /** The model's text, every text block in order. */
    public val text: String get() = blocks(events).filter { it.type == "text" }.joinToString("") { it.text }

    /** The text of each block splice signed as its own notice, in order. */
    public val notices: List<String> get() = blocks(events).filter { it.isNotice }.map { it.text }

    /** When the first notice that says [fragment] began to arrive, or null when none says it. */
    public fun noticeArrival(fragment: String): Duration? =
        blocks(events).firstOrNull { it.isNotice && fragment in it.text }?.let { arrivals.getOrNull(it.startedAt) }

    /** True when the stream reached message_stop. */
    public val completed: Boolean get() = events.any { WireJson.type(it) == "message_stop" }

    /** The stop_reason the stream ended with, when it named one. */
    public val stopReason: String?
        get() = events.filter { WireJson.type(it) == "message_delta" }
            .mapNotNull { WireJson.str(WireJson.obj(WireJson.parse(it.data), "delta"), "stop_reason") }
            .lastOrNull()

    /** True when model content reached the client before an in-band error. */
    public val contentBeforeError: Boolean
        get() {
            val errorAt = events.indexOfFirst { WireJson.type(it) == ERROR }
            return errorAt >= 0 && blocks(events.subList(0, errorAt)).any { it.isContent }
        }

    /**
     * The families this answer's status, error type and x-should-retry put the client in: design section 11's table
     * read backwards, so an answer two families share reads as both, and one no family writes reads as none.
     */
    public fun families(): Set<Family> = when {
        status == HTTP_OK && events.isNotEmpty() -> inBand(error?.type)
        status in HTTP_OK until HTTP_MULTIPLE_CHOICES -> emptySet()
        else -> overHttp(error?.type)
    }

    /** The answer as one line for an assertion message. */
    public fun describe(): String = listOf(
        "status=$status",
        "type=${error?.type}",
        "x-should-retry=$shouldRetry",
        "content-before-error=$contentBeforeError",
        "completed=$completed",
        "cut=$cut${failure?.let { " ($it)" }.orEmpty()}",
        "events=${events.map(WireJson::type)}",
        "text=${text.take(DESCRIBE_CHARS)}",
        "body=${body.take(DESCRIBE_CHARS)}",
    ).joinToString(" ")

    private fun inBand(type: String?): Set<Family> = when {
        type == null -> emptySet()
        contentBeforeError -> afterContent(type)
        else -> beforeContent(type)
    }

    /** After content the type is the cause's own, except T's, H's and L's, which they name. */
    private fun afterContent(type: String): Set<Family> = when (type) {
        OVERLOADED, "api_error" -> setOf(Family.R)
        INVALID_REQUEST -> setOf(Family.P, Family.T)
        "rate_limit_error" -> setOf(Family.R, Family.H, Family.L)
        else -> setOf(Family.P)
    }

    /** Committed with no content: R and L read overloaded, P, T and H read invalid request, and a client whose own
     *  credentials were refused reads authentication. */
    private fun beforeContent(type: String): Set<Family> = when (type) {
        OVERLOADED -> setOf(Family.R, Family.L)
        INVALID_REQUEST -> setOf(Family.P, Family.T, Family.H)
        "authentication_error" -> setOf(Family.P)
        else -> emptySet()
    }

    private fun overHttp(type: String?): Set<Family> {
        val noRetry = shouldRetry.equals("false", ignoreCase = true)
        return when {
            status == HTTP_TOO_MANY_REQUESTS -> if (noRetry) setOf(Family.H) else setOf(Family.L)
            noRetry -> refusal(type)
            status in HTTP_SERVER_ERROR until HTTP_STATUS_END -> setOf(Family.R)
            status in HTTP_BAD_REQUEST until HTTP_SERVER_ERROR -> setOf(Family.P)
            else -> emptySet()
        }
    }

    /** A status with x-should-retry false: P, and T too where the status and type are the ones T is answered with. */
    private fun refusal(type: String?): Set<Family> =
        if (status == HTTP_BAD_REQUEST && type == INVALID_REQUEST) setOf(Family.P, Family.T) else setOf(Family.P)

    private fun blocks(seen: List<SseEvent>): Collection<ClientBlock> {
        val byIndex = LinkedHashMap<Int, ClientBlock>()
        for ((at, event) in seen.withIndex()) {
            val data = WireJson.parse(event.data)
            val index = WireJson.str(data, "index")?.toIntOrNull()
            when {
                index == null -> Unit
                WireJson.type(event) == "content_block_start" -> byIndex[index] =
                    ClientBlock(WireJson.str(WireJson.obj(data, "content_block"), "type").orEmpty(), startedAt = at)
                WireJson.type(event) == "content_block_delta" ->
                    WireJson.obj(data, "delta")?.let { delta -> byIndex[index]?.add(delta) }
            }
        }
        return byIndex.values
    }

    private fun errorIn(text: String): ClientError? {
        val error = WireJson.obj(WireJson.parse(text), ERROR) ?: return null
        return ClientError(WireJson.str(error, "type").orEmpty(), WireJson.str(error, "message").orEmpty())
    }
}

/** What arrived so far, and whether the answer is over. */
private data class Progress(val answer: ClientAnswer, val ended: Boolean)

/** An answer still arriving: a test waits on what the client has seen so far, never on time. */
public class ClientStream internal constructor() {
    private val progress = MutableStateFlow(Progress(ClientAnswer(0, emptyMap(), emptyList(), "", cut = false), false))

    /** The request's own work, set once it starts. */
    @Volatile internal var call: Job? = null

    /** True once the answer ended or its connection dropped. */
    public val finished: Boolean get() = progress.value.ended

    /** What arrived so far. */
    public fun soFar(): ClientAnswer = progress.value.answer

    /** Returns once [fragment] is in the model's text, or the answer ended without it. */
    public suspend fun awaitText(fragment: String): ClientAnswer =
        progress.first { it.ended || fragment in it.answer.text }.answer

    /** Returns the whole answer once it ended. */
    public suspend fun finish(): ClientAnswer = progress.first { it.ended }.answer

    /** Hangs up, as Claude Code does when it abandons a request: the connection closes with the answer unread. */
    public fun hangUp() {
        call?.cancel()
    }

    internal fun update(change: (ClientAnswer) -> ClientAnswer) {
        progress.update { it.copy(answer = change(it.answer)) }
    }

    internal fun end(change: (ClientAnswer) -> ClientAnswer) {
        progress.update { Progress(change(it.answer), ended = true) }
    }
}

/** Server-sent events as the spec frames them: field lines up to a blank line, comments skipped. */
private class SseFraming(private val emit: (SseEvent) -> Unit) {
    private var name: String? = null
    private val data = mutableListOf<String>()

    fun line(line: String) {
        when {
            line.isEmpty() -> dispatch()
            line.startsWith("event:") -> name = line.removePrefix("event:").trim()
            line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            else -> Unit // a comment, such as a keepalive, or a field the client does not read
        }
    }

    private fun dispatch() {
        if (data.isNotEmpty()) emit(SseEvent(name, data.joinToString("\n")))
        name = null
        data.clear()
    }
}

/**
 * Claude Code's side of one head: it posts to /v1/messages on [port] as the client does, asking for [model], with
 * the session header, the user agent and the bearer token the client sends.
 */
public class ClientReader(
    private val port: Int,
    private val model: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The clock an event's arrival is read on ([ClientAnswer.arrivals]); a reader with none dates nothing. */
    private val clock: () -> Duration = { Duration.ZERO },
) : AutoCloseable {
    private val http = HttpClient(CIO) {
        engine { requestTimeout = 0 }
        expectSuccess = false
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Sends [request] and reads the whole answer. */
    public suspend fun send(request: ClientRequest = ClientRequest()): ClientAnswer = open(request).finish()

    /** Sends [request] and returns at once with the answer as it arrives. */
    public fun open(request: ClientRequest = ClientRequest()): ClientStream {
        val stream = ClientStream()
        stream.call = scope.launch { read(request, stream) }
        return stream
    }

    override fun close() {
        scope.cancel()
        http.close()
    }

    private suspend fun read(request: ClientRequest, stream: ClientStream) {
        val failure = runCatching {
            http.preparePost("http://127.0.0.1:$port/v1/messages") {
                header("Authorization", "Bearer ${request.bearer}")
                header("anthropic-version", ANTHROPIC_VERSION)
                header("User-Agent", CLIENT_AGENT)
                header(SESSION_HEADER, request.session)
                request.headers.forEach { (name, value) -> header(name, value) }
                header("Content-Type", ContentType.Application.Json.toString())
                setBody(body(request))
            }.execute { response -> readAnswer(response, stream) }
        }.exceptionOrNull()
        stream.end { answer ->
            failure?.let { answer.copy(cut = true, failure = "${it::class.simpleName}: ${it.message}") } ?: answer
        }
        if (failure is CancellationException) throw failure
    }

    private suspend fun readAnswer(response: HttpResponse, stream: ClientStream) {
        val headers = response.headers.entries().associate { (name, values) -> name.lowercase() to values }
        stream.update { it.copy(status = response.status.value, headers = headers) }
        if (response.contentType()?.match(ContentType.Text.EventStream) == true) {
            readEvents(response.bodyAsChannel(), stream)
        } else {
            val body = response.bodyAsText()
            stream.update { it.copy(body = body) }
        }
    }

    private suspend fun readEvents(channel: ByteReadChannel, stream: ClientStream) {
        val framing = SseFraming { event ->
            val at = clock()
            stream.update { it.copy(events = it.events + event, arrivals = it.arrivals + at) }
        }
        while (true) {
            framing.line(channel.readLine() ?: break)
        }
    }

    private fun body(request: ClientRequest): String = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("stream", request.stream)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", request.text)
            }
        }
        request.extra.forEach { (name, value) -> put(name, value) }
    }.toString()
}
