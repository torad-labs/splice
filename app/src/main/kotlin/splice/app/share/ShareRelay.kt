// NEW: LAN share, phase 1 (docs/specs/lan-share.md). A client's turn, handed to the head it names on this
// machine's loopback with the head's own turn key in place of the client's token, and the head's answer
// streamed back as it arrives. The head runs the turn exactly as for a session on this machine: its
// admission, inflight gate, account selection, cooldowns and honest failures all apply unchanged, and no
// credential of any provider leaves this process. An event stream goes back through SseChannelResponse, the
// daemon's one streaming writer (kt-head-sse-drains-on-exit); any other answer goes back whole.
package splice.app.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import splice.core.config.TurnKey
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.http.SseChannelResponse
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal class ShareRelay(
    private val turnKey: TurnKey,
    /** Blocking JDK I/O runs here: the request body read, the send, and each read of the head's answer. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .build()

    /** [target] is the head's own path and query (`/v1/messages?beta=true`). */
    suspend fun relay(call: ApplicationCall, head: String, port: Int, target: String) {
        val body = withContext(io) { call.receiveStream().use(InputStream::readAllBytes) }
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
        "warning", "from", "origin", "referer",
    )

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

private const val CONNECT_TIMEOUT_MS = 5_000L
private const val PUMP_BUFFER_BYTES = 16 * 1024
private const val END_OF_STREAM = -1
