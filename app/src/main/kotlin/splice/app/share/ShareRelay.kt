// NEW: LAN share, phase 1 (docs/specs/lan-share.md). A client's turn, handed to the head it names on this
// machine's loopback with the head's own turn key in place of the client's token, and the head's answer
// streamed back as it arrives. The head runs the turn exactly as for a session on this machine: its
// admission, inflight gate, account selection, cooldowns and honest failures all apply unchanged, and no
// credential of any provider leaves this process. An event stream goes back through SseChannelResponse, the
// daemon's one streaming writer (kt-head-sse-drains-on-exit); any other answer goes back whole. The client's body
// is read under that head's own limits (its maxRequestBytes and requestReadTimeoutMs, read live per request as the
// head reads them), so a body the head would refuse is refused here with the head's own 413 or 408, before this
// listener holds more of it than the head would.
package splice.app.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splice.core.config.ConfigService
import splice.core.config.TurnKey
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.head.SHARE_RELAY_HEADER
import splice.head.admission.AdmissionErrorBody
import splice.head.admission.INVALID_REQUEST_ERROR
import splice.http.SseChannelResponse
import splice.upstream.failure.SseSpuriousWakeupException
import splice.upstream.transport.ChannelReads
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class ShareRelay(
    private val turnKey: TurnKey,
    /** Each head's live config, for the body limits that head enforces itself. */
    private val config: ConfigService,
    /** Blocking JDK I/O runs here: the send, and each read of the head's answer. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(RELAY_CONNECT_TIMEOUT_MS))
        .build()

    /** [target] is the head's own path and query (`/v1/messages?beta=true`). */
    suspend fun relay(call: ApplicationCall, head: String, port: Int, target: String) {
        val body = bounded(call, head) ?: return
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$target"))
            .method(call.request.httpMethod.value, publisher)
        call.request.headers.forEach { name, values ->
            if (name.lowercase() !in notForwarded) values.forEach { request.header(name, it) }
        }
        request.header("Authorization", "Bearer ${turnKey.get()}")
        // The client's own head scales the counts for its window, so the head here reports them raw (TurnWiring).
        request.header(SHARE_RELAY_HEADER, "1")
        val streamed = HttpResponse.BodyHandlers.ofInputStream()
        val sent = withContext(io) { Cancellables.runCatchingCancellable { http.send(request.build(), streamed) } }
        val response = sent.getOrElse { failure ->
            log("[share] head '$head' did not answer on :$port: ${SafeFailureText.render(failure)}\n")
            val refusal = ShareErrors().body("head '$head' is not answering on the sharing machine")
            call.respondText(refusal, ContentType.Application.Json, HttpStatusCode.fromValue(HttpStatus.BAD_GATEWAY))
            return
        }
        answer(call, response)
    }

    /** The client's body under [head]'s own maxRequestBytes and requestReadTimeoutMs, as the head reads it. */
    private suspend fun bounded(call: ApplicationCall, head: String): ByteArray? {
        val limits = config.getConfig(head)
        return body(call, limits.maxRequestBytes, limits.requestReadTimeoutMs)
    }

    /** [call]'s body, or null once it is refused the way a head's own admission refuses one: 413 for a body declared
     *  or read past [cap], 408 for one still arriving after [readTimeoutMs] or torn (AdmissionGate's
     *  materializeBodyOrRespond: same status, same envelope, same words). The join route reads its body here too. */
    suspend fun body(call: ApplicationCall, cap: Int, readTimeoutMs: Long): ByteArray? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val refusal = try {
            val body = if (declared != null && declared > cap) {
                null
            } else {
                withTimeout(readTimeoutMs) { capped(call.receiveChannel(), cap) }
            }
            if (body != null) return body
            HttpStatusCode(HttpStatus.CONTENT_TOO_LARGE, "Content Too Large") to "request body exceeds $cap bytes"
        } catch (_: TimeoutCancellationException) {
            HttpStatusCode.RequestTimeout to "request body read timed out"
        } catch (_: SseSpuriousWakeupException) {
            HttpStatusCode.RequestTimeout to "request body stream interrupted"
        }
        val (status, message) = refusal
        val envelope = AdmissionErrorBody.render(INVALID_REQUEST_ERROR, message)
        call.respondText(envelope, ContentType.Application.Json, status)
        return null
    }

    /** The body whole, or null as soon as it passes [cap]: no more than the cap and one read is ever held. */
    private suspend fun capped(channel: ByteReadChannel, cap: Int): ByteArray? {
        val whole = ByteArrayOutputStream()
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        var count = ChannelReads.readAvailableOrEof(channel, buffer)
        while (count >= 0) {
            if (whole.size().toLong() + count > cap) return null
            whole.write(buffer, 0, count)
            count = ChannelReads.readAvailableOrEof(channel, buffer)
        }
        return whole.toByteArray()
    }

    private suspend fun answer(call: ApplicationCall, response: HttpResponse<InputStream>) {
        response.headers().map().forEach { (name, values) ->
            if (name.lowercase() !in notReturned) values.forEach { call.response.headers.append(name, it) }
        }
        val status = HttpStatusCode.fromValue(response.statusCode())
        val type = response.headers().firstValue("content-type").map { ContentType.parse(it) }
            .orElse(ContentType.Application.OctetStream)
        if (type.match(ContentType.Text.EventStream)) {
            call.response.status(status)
            call.respond(SseChannelResponse { out -> response.body().use { input -> pump(input, out) } })
        } else {
            val whole = withContext(io) { response.body().use(InputStream::readAllBytes) }
            call.respondBytes(whole, type, status)
        }
    }

    /** Chunk by chunk, flushed each time: an event (or the head's keepalive ping) reaches the client the moment the
     *  head writes it. A client that went away fails the write, and closing the head's stream then hangs up on the
     *  head, which sees its own client leave. A head that hangs up mid-stream ends the relay there. */
    private suspend fun pump(input: InputStream, out: ByteWriteChannel) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        while (true) {
            val read = withContext(io) {
                Cancellables.runCatchingCancellable { input.read(buffer) }
                    .getOrElse { failure -> if (failure is IOException) END_OF_STREAM else throw failure }
            }
            if (read < 0) return
            out.writeFully(buffer, 0, read)
            out.flush()
        }
    }

    // Hop-by-hop, framing, the client's own credential, and the names java.net.http refuses to set. Host
    // becomes 127.0.0.1, which the head's own DNS-rebinding guard requires.
    private val notForwarded = setOf(
        "host", "connection", "content-length", "transfer-encoding", "upgrade", "expect", "keep-alive", "te",
        "trailer", "proxy-authorization", "proxy-connection", "authorization", "x-api-key", "date", "via",
        "warning", "from", "origin", "referer", SHARE_RELAY_HEADER,
    )

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

// why: the head is on this machine's loopback, where a live listener accepts at once; a connect still waiting after
// five seconds is a head that is not answering, and the client hears a 502 in words instead of hanging.
private const val RELAY_CONNECT_TIMEOUT_MS = 5_000L

// why: one read of the head's answer: an SSE event is a few hundred bytes, so 16 KiB carries a whole burst per
// flush while holding little memory for each relayed stream. One read of the client's body is the same size, the
// head's own body read (RequestBodyReader), so the cap is passed by at most one read.
private const val PUMP_BUFFER_BYTES = 16 * 1024

// why: what InputStream.read answers at the end of the stream; a failed read of the head's answer ends the relay the
// same way, as the head hanging up.
private const val END_OF_STREAM = -1
