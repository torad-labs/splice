// NEW: server mode (docs/specs/server-mode.md, F8). `POST /server/otlp/v1/{logs,metrics,traces}`, served where
// `[server.telemetry] forward_to` names the operator's collector. A laptop's Claude Code exports its own telemetry here
// over OTLP/HTTP, with the person's key, as JSON or protobuf. The listener has checked that key before this sees the
// request; the relay writes the key's person and their team on every resource (ResourceStamp), whatever the client
// claimed, and posts the request to the collector under the same signal's path, in the same encoding, with none of
// the client's headers but its content type: never the person's key. The collector's answer is handed back as it
// came. A collector that fails or can't be reached is a 502, 503 or 504, which an OTLP exporter retries, and the log
// says it once when it starts failing and once when it answers again. A server with no relay has no such route.
package splice.server.telemetry

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.future.await
import splice.core.topology.ServerTelemetry
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.share.CappedBodies
import splice.share.RefusalVoice
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

internal class TelemetryRelay(
    telemetry: ServerTelemetry,
    private val voice: RefusalVoice,
    private val log: LogSink,
) {
    private val collector = telemetry.collector
    private val bodies = CappedBodies(voice)
    private val json = JsonStamp()
    private val proto = ProtoStamp()
    private val failing = AtomicBoolean(false)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(COLLECTOR_CONNECT_MS))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Relays [call], for [rest] the path after `/server/otlp`, to the collector with [stamp] on every resource. */
    suspend fun answer(call: ApplicationCall, rest: List<String>, stamp: ResourceStamp) {
        val signal = OtlpSignal.entries.firstOrNull { rest == listOf(OTLP_VERSION, it.segment) }
            ?.takeIf { call.request.httpMethod == HttpMethod.Post }
        val type = call.request.headers[HttpHeaders.ContentType].orEmpty()
        val asJson = type.startsWith(JSON_TYPE)
        when {
            signal == null -> {
                val routes = OtlpSignal.entries.joinToString(",") { it.segment }
                voice.refuse(call, HttpStatus.NOT_FOUND, "no such path; OTLP is POST /server/otlp/v1/{$routes}")
            }
            !asJson && !PROTOBUF_TYPES.any(type::startsWith) ->
                voice.refuse(call, HttpStatus.BAD_REQUEST, "an OTLP request is $JSON_TYPE or ${PROTOBUF_TYPES[0]}")
            else -> relayed(call, signal, type, asJson, stamp)
        }
    }

    /** Reads [call]'s body of [signal] in [type] (JSON when [asJson]), stamps it with [stamp] and forwards it. */
    private suspend fun relayed(
        call: ApplicationCall,
        signal: OtlpSignal,
        type: String,
        asJson: Boolean,
        stamp: ResourceStamp,
    ) {
        val body = sent(call) ?: return
        val stamped = if (asJson) json.stamped(signal, body, stamp) else proto.stamped(body, stamp)
        if (stamped == null) {
            voice.refuse(call, HttpStatus.BAD_REQUEST, "this is not an OTLP ${signal.segment} request")
        } else {
            forward(call, signal, type, stamped)
        }
    }

    /** [call]'s body, inflated when it came gzipped, or null once refused. */
    private suspend fun sent(call: ApplicationCall): ByteArray? {
        val raw = bodies.read(call, MAX_RELAY_BODY_BYTES, BODY_READ_MS) ?: return null
        val encoding = call.request.headers[HttpHeaders.ContentEncoding]?.trim()?.lowercase()
        if (encoding.isNullOrEmpty() || encoding == IDENTITY) return raw
        val inflated = if (encoding == GZIP) inflated(raw) else null
        when {
            inflated == null ->
                voice.refuse(call, HttpStatus.BAD_REQUEST, "an OTLP request is sent as it is or gzipped")
            inflated.size > MAX_RELAY_BODY_BYTES ->
                voice.refuse(call, HttpStatus.CONTENT_TOO_LARGE, "request body exceeds $MAX_RELAY_BODY_BYTES bytes")
        }
        return inflated?.takeIf { it.size <= MAX_RELAY_BODY_BYTES }
    }

    /** [raw] gunzipped, one byte past the cap at most; null when it is not gzip. */
    private fun inflated(raw: ByteArray): ByteArray? = try {
        GZIPInputStream(raw.inputStream()).use { it.readNBytes(MAX_RELAY_BODY_BYTES + 1) }
    } catch (_: IOException) {
        null
    }

    /** Posts [body] to the collector as [signal] in [type], and answers [call] with what the collector said. */
    private suspend fun forward(call: ApplicationCall, signal: OtlpSignal, type: String, body: ByteArray) {
        val request = HttpRequest.newBuilder(URI.create("$collector/$OTLP_VERSION/${signal.segment}"))
            .timeout(Duration.ofMillis(COLLECTOR_ANSWER_MS))
            .header(HttpHeaders.ContentType, type)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val sent = Cancellables.runCatchingCancellable {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
        }
        val answer = sent.getOrElse { failure ->
            val status = if (failure is HttpTimeoutException) HttpStatus.GATEWAY_TIMEOUT else HttpStatus.BAD_GATEWAY
            return failed(call, status, "can't be reached: ${SafeFailureText.render(failure)}")
        }
        if (answer.statusCode() !in HttpStatus.OK..HttpStatus.MAX_SUCCESS) {
            val retry = answer.statusCode() == HttpStatus.TOO_MANY_REQUESTS ||
                answer.statusCode() == HttpStatus.SERVICE_UNAVAILABLE
            val status = if (retry) HttpStatus.SERVICE_UNAVAILABLE else HttpStatus.BAD_GATEWAY
            return failed(call, status, "answered ${answer.statusCode()}")
        }
        if (failing.compareAndSet(true, false)) log("[server] the telemetry collector at $collector answers again\n")
        val answered = answer.headers().firstValue(HttpHeaders.ContentType).orElse(type)
        call.respondBytes(answer.body(), ContentType.parse(answered), HttpStatusCode.fromValue(answer.statusCode()))
    }

    /** Answers [call] [status] for a collector that [why], said in the log when it starts failing. */
    private suspend fun failed(call: ApplicationCall, status: Int, why: String) {
        if (failing.compareAndSet(false, true)) {
            log("[server] the telemetry collector at $collector $why; laptops' telemetry waits until it answers\n")
        }
        voice.refuse(call, status, "the operator's telemetry collector $why")
    }
}

/** The path the relay answers under, after the server's own root: `/server/otlp`. */
internal const val OTLP_ROUTE: String = "otlp"

// The OTLP/HTTP version segment each signal's path starts with.
private const val OTLP_VERSION = "v1"

private const val JSON_TYPE = "application/json"
private val PROTOBUF_TYPES = listOf("application/x-protobuf", "application/protobuf")
private const val GZIP = "gzip"
private const val IDENTITY = "identity"

// why: Claude Code exports a batch every few seconds of at most a few hundred records; 8 MiB is far past any batch it
// sends and bounds what one request can make the server hold.
private const val MAX_RELAY_BODY_BYTES = 8 * 1024 * 1024

// why: a batch is sent whole in well under a second on any link; a body still arriving after 30 s is a stalled client.
private const val BODY_READ_MS = 30_000L

// why: the collector is the operator's own, near the server; five seconds to connect says one that is down.
private const val COLLECTOR_CONNECT_MS = 5_000L

// why: a collector takes a batch in milliseconds; past ten seconds the exporter on the laptop is told to try again.
private const val COLLECTOR_ANSWER_MS = 10_000L
