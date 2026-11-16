// NEW: the client's half of a relay (docs/specs/lan-share.md; docs/specs/server-mode.md, F4). A head's answer, given
// to the client that asked: an event stream goes back through SseChannelResponse, the daemon's one streaming writer,
// chunk by chunk as the head writes it, and any other answer goes back whole, under the head's own status.
//
// Server mode keeps a streamed turn's client alive while its answer is silent (QuietWire). A model server prefilling
// a long prompt keeps the head silent before its first byte, headers included, so a streamed turn whose head has not
// answered in a second has its stream opened to the client then, a keepalive written at once and at each interval,
// and the head's events after them when they come. The status has gone out as 200 by then, so a head that refuses
// such a turn late, or does not answer it, is said as the stream's `error` event, in the head's own words where it
// gave them. A head that answers within the second answers as it always did, its own status included. The second
// and the interval are waited out on the relay's clock (RelayClock).
//
// A relay that answers for a provider's own server (F4, openai-route) reads that server's stream through a StreamEdit,
// event by event, and gives it its ending only when the server ended it whole.
//
// An answer that breaks off before its end is never passed on as if it had ended (F4, "an answer the upstream cuts
// short is an error the client sees"). One that is no stream is read whole before any of it is given, so a body that
// breaks off is a 502 in the relay's own words. A stream read event by event has its client's stream standing between
// two events when the server's breaks, so it ends with the relay's error event and never with the ending of a whole
// one. A stream relayed byte for byte may have broken inside an event, where nothing well-formed can follow: it ends
// there, as it always did. Each is said in the log, and a relay that was cancelled broke nothing off.
//
// A head's answer goes back with the head's own headers: the head is this machine's, and what it sends back it means
// for its client. A provider's own server is not (F4, "the provider's own response headers stay at the server"): what
// it sends beside its answer (a cookie, its request ids, its rate-limit counts) is its business with this machine and
// never the client's. Of such an answer the client gets the status, the body, the body's type and, when the server
// refused, its `Retry-After`, which says when to ask again.
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import splice.core.util.Cancellables
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.http.SseChannelResponse
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse
import kotlin.time.Duration

/** What a relay tells its client of one request: [who] it waits on, as the client hears it named when that one gives
 *  no answer or breaks one off, a keepalive each [every] while a streamed answer is silent (null: none), and a
 *  streamed answer as [edit] reads it (null: byte for byte). [outside] says the answer is a server's that is no part
 *  of this machine, whose own headers stay here. */
internal data class ClientWait(
    val who: String,
    val every: Duration? = null,
    val edit: StreamEdit? = null,
    val outside: Boolean = false,
) {
    /** What the client hears when [who] gives no answer. */
    val silent: String get() = "$who is not answering"

    /** What the client hears when [who] broke its answer off before it ended. */
    val cut: String get() = "$who broke off its answer before it ended"
}

internal class HeadAnswers(
    /** Blocking JDK I/O runs here: each read of the answer. */
    private val io: CoroutineDispatcher,
    private val speaker: RelaySpeaker,
    /** What a kept-alive client's silences are timed on. */
    private val clock: RelayClock,
    private val log: LogSink,
) {
    private val errors get() = speaker.errors

    /** [sent] as [call]'s answer: the head's own, or that it is not answering. */
    suspend fun given(call: ApplicationCall, sent: Result<HttpResponse<InputStream>>, wait: ClientWait) {
        val response = sent.getOrElse { return errors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, wait.silent) }
        val status = HttpStatusCode.fromValue(response.statusCode())
        val type = typeOf(response)
        if (type.match(ContentType.Text.EventStream)) {
            returned(call, response, wait)
            call.response.status(status)
            call.respond(SseChannelResponse { out -> response.body().use { pump(it, quiet(out, wait), wait) } })
        } else {
            val whole = whole(response, wait) ?: return errors.refuse(call, HttpStatus.BAD_GATEWAY, wait.cut)
            returned(call, response, wait)
            call.respondBytes(whole, type, status)
        }
    }

    /** [call]'s stream opened now, before its head has answered, and kept alive until [sending] ends: then the
     *  head's events, or its refusal or its silence as the stream's `error` event. */
    suspend fun early(call: ApplicationCall, sending: Deferred<Result<HttpResponse<InputStream>>>, wait: ClientWait) {
        call.respond(
            SseChannelResponse { out ->
                val wire = quiet(out, wait)
                wire.keepalive()
                wire.during { sending.await() }.fold(
                    onSuccess = { response -> late(response, wire, wait) },
                    onFailure = { wire.error(said(HttpStatus.SERVICE_UNAVAILABLE, wait.silent)) },
                )
            },
        )
    }

    /** The head's answer on a stream that is already open: its events, or what it answered in their place. */
    private suspend fun late(response: HttpResponse<InputStream>, wire: QuietWire, wait: ClientWait) {
        if (typeOf(response).match(ContentType.Text.EventStream)) return response.body().use { pump(it, wire, wait) }
        val whole = whole(response, wait) ?: return wire.error(said(HttpStatus.BAD_GATEWAY, wait.cut))
        wire.error(errors.event(errors.relayed(response.statusCode(), String(whole))))
    }

    /** The headers of [response] that go back with it. A head's all do, but the ones that frame it. Of a server's
     *  that is outside this machine, only `Retry-After`, and only when it refused. */
    private fun returned(call: ApplicationCall, response: HttpResponse<InputStream>, wait: ClientWait) {
        val refused = response.statusCode() >= HttpStatus.BAD_REQUEST
        response.headers().map().forEach { (name, values) ->
            val back = name.lowercase().let { if (wait.outside) refused && it == RETRY_AFTER else it !in notReturned }
            if (back) values.forEach { call.response.headers.append(name, it) }
        }
    }

    /** The client's side of the stream [out], kept alive as [wait] asks. */
    private fun quiet(out: ByteWriteChannel, wait: ClientWait): QuietWire = QuietWire(out, wait.every, clock)

    /** [message] under [status], as the event that says it on a stream already open. */
    private fun said(status: Int, message: String): String = errors.event(errors.body(status, message))

    private fun typeOf(response: HttpResponse<InputStream>): ContentType =
        response.headers().firstValue("content-type").map { ContentType.parse(it) }
            .orElse(ContentType.Application.OctetStream)

    /** The whole of an answer that is no stream, or null when it broke off before its end. */
    private suspend fun whole(response: HttpResponse<InputStream>, wait: ClientWait): ByteArray? {
        val read = runInterruptible(io) {
            Cancellables.runCatchingCancellable { response.body().use(InputStream::readAllBytes) }
        }
        return read.getOrElse { failure -> null.also { brokeOff(failure, wait) } }
    }

    /** Chunk by chunk, flushed each time: an event (or the head's keepalive ping) reaches the other machine the moment
     *  the head writes it. A client that went away fails the write, and closing the head's stream then hangs up on the
     *  head, which sees its own client leave. With an edit the chunks go through it, an event at a time, and a stream
     *  that ended whole is given the edit's ending. A stream that broke off ends the relay there, after the relay's
     *  error event when it is read through an edit. */
    private suspend fun pump(input: InputStream, wire: QuietWire, wait: ClientWait) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        val edited = wait.edit?.let { EditedWire(wire, it) }
        while (true) {
            val read = wire.during {
                runInterruptible(io) { Cancellables.runCatchingCancellable { input.read(buffer) } }
            }
            val count = read.getOrElse { failure ->
                brokeOff(failure, wait)
                if (edited != null) wire.error(said(HttpStatus.BAD_GATEWAY, wait.cut))
                return
            }
            if (count == END_OF_STREAM) {
                edited?.end()
                return
            }
            if (edited == null) wire.write(buffer, count) else edited.write(buffer, count)
        }
    }

    /** A read of the answer ended in [failure]: the answer broke off, which is said in the log. A relay that was
     *  cancelled fails its read too, and ends here as cancelled. */
    private suspend fun brokeOff(failure: Throwable, wait: ClientWait) {
        currentCoroutineContext().ensureActive()
        if (failure !is IOException) throw failure
        log("[${speaker.tag}] ${wait.cut}: ${SafeFailureText.render(failure)}\n")
    }

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

// why: one read of the head's answer: an SSE event is a few hundred bytes, so 16 KiB carries a whole burst per flush
// while holding little memory for each relayed stream.
private const val PUMP_BUFFER_BYTES = 16 * 1024

// What InputStream.read answers at the end of the stream.
private const val END_OF_STREAM = -1

// The one header of a refusal that is for the client whoever refused: how long to wait before asking again.
private const val RETRY_AFTER = "retry-after"
