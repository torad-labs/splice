// NEW: LAN share (docs/specs/lan-share.md). A request from another machine's Claude Code, handed to this machine's head
// on loopback (a turn) or to its control plane (a statusline), with this machine's turn key in place of whatever the
// other machine sent, and the answer streamed back as it arrives. The head runs the turn exactly as for a session here:
// its admission, account selection, cooldowns and honest failures all apply unchanged, and no credential of any
// provider leaves this process. An event stream goes back through SseChannelResponse, the daemon's one streaming
// writer; any other answer goes back whole. The body is read under the head's own limits first (its maxRequestBytes
// and read timeout), so a body the head would refuse is refused here in the head's words, before this listener holds
// more of it than the head would. A head that does not answer is said as that, in this machine's name: a 503 Claude
// Code prints, never a 529 it retries in silence as "overloaded".
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
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
    private val controlPort: Int,
    private val machine: String,
    /** Blocking JDK I/O runs here: the send, and each read of the answer. */
    private val io: CoroutineDispatcher,
    private val log: LogSink,
) {
    /** What a statusline's body passes before it is relayed: the listener pins the session it names to the login the
     *  address chose, so the line shows that login from its first draw, before the session's first turn. */
    internal fun interface StatuslineAdmission {
        /** Why [body]'s statusline may not be drawn, or null once it may. */
        fun refusal(body: ByteArray): String?
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(RELAY_CONNECT_TIMEOUT_MS))
        .build()

    /** [path] (`/v1/messages?beta=true`) on [head], under the head's own body limits. */
    suspend fun turn(call: ApplicationCall, head: SharedHead, path: String) {
        val body = body(call, head.maxRequestBytes(), head.requestReadTimeoutMs()) ?: return
        val silent = "${head.command} on $machine is not answering"
        forward(call, URI.create("http://127.0.0.1:${head.port}$path"), body, silent)
    }

    /** The control plane's statusline for [head], fed the session's JSON as Claude Code feeds its statusline, once
     *  [admission] has read the body: a refusal it returns is answered instead. */
    suspend fun statusline(call: ApplicationCall, head: SharedHead, admission: StatuslineAdmission) {
        val body = body(call, STATUSLINE_BODY_MAX_BYTES, STATUSLINE_READ_MS) ?: return
        val refusal = withContext(io) { admission.refusal(body) }
        if (refusal != null) return ShareErrors.refuse(call, HttpStatus.BAD_REQUEST, refusal)
        val silent = "$machine's statusline for ${head.command} is not answering"
        forward(call, URI.create("http://127.0.0.1:$controlPort/statusline/${head.key}"), body, silent)
    }

    private suspend fun forward(call: ApplicationCall, target: URI, body: ByteArray, silent: String) {
        val publisher = if (body.isEmpty()) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        val request = HttpRequest.newBuilder(target).method(call.request.httpMethod.value, publisher)
        call.request.headers.forEach { name, values ->
            if (name.lowercase() !in notForwarded) values.forEach { request.header(name, it) }
        }
        request.header(HttpHeaders.Authorization, "Bearer ${turnKey.get()}")
        val streamed = HttpResponse.BodyHandlers.ofInputStream()
        val sent = withContext(io) { Cancellables.runCatchingCancellable { http.send(request.build(), streamed) } }
        val response = sent.getOrElse { failure ->
            log("[share] $silent on :${target.port}: ${SafeFailureText.render(failure)}\n")
            ShareErrors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, silent)
            return
        }
        answer(call, response)
    }

    /** [call]'s body, or null once it is refused the way a head's own admission refuses one: 413 for a body declared
     *  or read past [cap], 408 for one still arriving after [readTimeoutMs]. */
    private suspend fun body(call: ApplicationCall, cap: Int, readTimeoutMs: Long): ByteArray? {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.takeIf { it >= 0L }
        val read = if (declared != null && declared > cap) {
            null
        } else {
            try {
                // One byte past the cap is enough to know the body is too large; no more is ever held.
                withTimeout(readTimeoutMs) { call.receiveChannel().readBuffer(cap + 1L).readByteArray() }
            } catch (_: TimeoutCancellationException) {
                val why = "the request body was still arriving after ${readTimeoutMs}ms"
                ShareErrors.refuse(call, HttpStatus.REQUEST_TIMEOUT, why)
                return null
            }
        }
        if (read != null && read.size <= cap) return read
        ShareErrors.refuse(call, HttpStatus.CONTENT_TOO_LARGE, "request body exceeds $cap bytes")
        return null
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

    /** Chunk by chunk, flushed each time: an event (or the head's keepalive ping) reaches the other machine the moment
     *  the head writes it. A client that went away fails the write, and closing the head's stream then hangs up on the
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

    // Hop-by-hop, framing, the other machine's own token, and the names java.net.http refuses to set. Host becomes
    // 127.0.0.1, which the head's own DNS-rebinding guard requires.
    private val notForwarded = setOf(
        "host", "connection", "content-length", "transfer-encoding", "upgrade", "expect", "keep-alive", "te",
        "trailer", "proxy-authorization", "proxy-connection", "authorization", "x-api-key", "date", "via",
        "warning", "from", "origin", "referer",
    )

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

// why: the head and the control plane are on this machine's loopback, where a live listener accepts at once; a connect
// still waiting after five seconds is one that is not answering, and the other machine hears that in words.
private const val RELAY_CONNECT_TIMEOUT_MS = 5_000L

// why: one read of the head's answer: an SSE event is a few hundred bytes, so 16 KiB carries a whole burst per flush
// while holding little memory for each relayed stream.
private const val PUMP_BUFFER_BYTES = 16 * 1024

// why: what InputStream.read answers at the end of the stream; a failed read of the head's answer ends the relay the
// same way, as the head hanging up.
private const val END_OF_STREAM = -1

// why: Claude Code's statusline input is one session's JSON, a few KiB; 256 KiB holds it with room, and nothing larger
// is read before it is refused.
private const val STATUSLINE_BODY_MAX_BYTES = 256 * 1024

// why: that JSON arrives in one or two packets on a LAN; a statusline still unfinished after five seconds is refused.
private const val STATUSLINE_READ_MS = 5_000L
