// NEW: the client's half of a relay (docs/specs/lan-share.md; docs/specs/server-mode.md, F4). A head's answer, given
// to the client that asked: an event stream goes back through SseChannelResponse, the daemon's one streaming writer,
// chunk by chunk as the head writes it, and any other answer goes back whole, under the head's own status.
//
// Server mode keeps a streamed turn's client alive while its answer is silent (QuietWire). A model server prefilling
// a long prompt keeps the head silent before its first byte, so a streamed turn whose answer has not started in a
// second has its stream opened to the client then, a keepalive written at once and at each interval, and the head's
// events after them when they come. An answer has started when the first byte of it has come, or when it came and
// is no stream: response headers alone start nothing, because one server holds them back through its prefill and
// another sends them at once and prefills after them (AnswerReads), and the client of either hears nothing until
// something is written to it. The status has gone out as 200 by then, so a head that refuses such a turn late, or
// does not answer it, is said as the stream's `error` event, in the head's own words where it gave them. A head
// whose answer starts within the second answers as it always did, its own status included. The second and the
// interval are waited out on the relay's clock (RelayClock).
//
// A relay that answers for a provider's own server (F4, openai-route) reads that server's stream through a StreamEdit,
// event by event, and gives it its ending only when the server ended it whole. An answer of that server's that is no
// stream may be read too, through a WholeEdit, when its client asked for less than the server answers. A streamed
// request to such a server is kept alive the same way a head's turn is (F4, openai-keepalive), and what ends a stream
// that was opened before its server answered is the error event its client's SDK reads (RefusalVoice.event).
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
import io.ktor.http.isSuccess
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

/** What a relay tells its client of one request: a keepalive each [every] while a streamed answer is silent (null:
 *  none), and a streamed answer as [edit] reads it (null: byte for byte), or one that is no stream as [whole] does
 *  (null: as it is). [outside] says the answer is a server's that is no part of this machine, whose own headers stay
 *  here. */
internal data class ClientWait(
    val every: Duration? = null,
    val edit: StreamEdit? = null,
    val whole: WholeEdit? = null,
    val outside: Boolean = false,
)

/** How asking for an answer ended. */
internal sealed class Sent {
    /** [who] gave [response]; [who] is how the client hears that one named, should the answer break off. [reads]
     *  is the reading of its event stream when that was begun ahead of relaying it (HeadAnswers.started). */
    class Answered(
        val response: HttpResponse<InputStream>,
        val who: String,
        val reads: AnswerReads? = null,
    ) : Sent() {
        /** What the client hears when [who] broke its answer off before it ended. */
        val cut: String get() = "$who broke off its answer before it ended"
    }

    /** No one gave an answer, and the client hears [why]: who it is that is not answering. */
    class Silent(val why: String) : Sent()
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

    /** [sent] as [call]'s answer: the answer that was given, or that no one is answering. */
    suspend fun given(call: ApplicationCall, sent: Sent, wait: ClientWait) {
        val answered = when (sent) {
            is Sent.Silent -> return errors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, sent.why)
            is Sent.Answered -> sent
        }
        val response = answered.response
        if (!typeOf(response).match(ContentType.Text.EventStream)) return unstreamed(call, answered, wait)
        returned(call, response, wait)
        call.response.status(HttpStatusCode.fromValue(response.statusCode()))
        call.respond(SseChannelResponse { out -> pump(answered, quiet(out, wait), wait) })
    }

    /** [sent], back once its answer has started: an event stream when its first bytes have come, or it has ended or
     *  broken off without any, and whatever else [sent] is, as it stands. A relay that keeps its client alive waits
     *  on this, so a server that sent its response headers and then fell silent is still one that has not answered. */
    suspend fun started(sent: Sent): Sent {
        if (sent !is Sent.Answered || !typeOf(sent.response).match(ContentType.Text.EventStream)) return sent
        val reads = AnswerReads(sent.response.body(), io).also { it.begin() }
        return Sent.Answered(sent.response, sent.who, reads)
    }

    /** An answer that is no stream, given to [call]. It is read whole before any of it is given, so one that breaks
     *  off is a 502 and never half a body; and one the server did not refuse is given as [wait]'s reader of whole
     *  answers reads it, when it has one. */
    private suspend fun unstreamed(call: ApplicationCall, answered: Sent.Answered, wait: ClientWait) {
        val response = answered.response
        val whole = whole(answered) ?: return errors.refuse(call, HttpStatus.BAD_GATEWAY, answered.cut)
        val status = HttpStatusCode.fromValue(response.statusCode())
        val edit = wait.whole?.takeIf { status.isSuccess() }
        when (val read = edit?.read(whole) ?: WholeAnswer.Given(whole)) {
            is WholeAnswer.Refused -> errors.refuse(call, read.status, read.why)
            is WholeAnswer.Given -> {
                returned(call, response, wait)
                call.respondBytes(read.body, typeOf(response), status)
            }
        }
    }

    /** [call]'s stream opened now, before anyone has answered, and kept alive until [sending] ends: then the answer's
     *  events, or a refusal or a silence as the stream's error event. */
    suspend fun early(call: ApplicationCall, sending: Deferred<Sent>, wait: ClientWait) {
        call.respond(
            SseChannelResponse { out ->
                val wire = quiet(out, wait)
                wire.keepalive()
                when (val sent = wire.during { sending.await() }) {
                    is Sent.Answered -> late(sent, wire, wait)
                    is Sent.Silent -> wire.error(said(HttpStatus.SERVICE_UNAVAILABLE, sent.why))
                }
            },
        )
    }

    /** An answer on a stream that is already open: its events, or what was answered in their place. */
    private suspend fun late(answered: Sent.Answered, wire: QuietWire, wait: ClientWait) {
        val response = answered.response
        if (typeOf(response).match(ContentType.Text.EventStream)) return pump(answered, wire, wait)
        val whole = whole(answered) ?: return wire.error(said(HttpStatus.BAD_GATEWAY, answered.cut))
        wire.error(errors.event(errors.relayed(response.statusCode(), String(whole), answered.who)))
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
    private suspend fun whole(answered: Sent.Answered): ByteArray? {
        val read = runInterruptible(io) {
            Cancellables.runCatchingCancellable { answered.response.body().use(InputStream::readAllBytes) }
        }
        return read.getOrElse { failure -> null.also { brokeOff(failure, answered) } }
    }

    /** [answered]'s stream, chunk by chunk, flushed each time: an event (or the head's keepalive ping) reaches the
     *  other machine the moment it is written. A client that went away fails the write, and closing the stream then
     *  hangs up on the one that answered, which sees its own client leave. With an edit the chunks go through it, an
     *  event at a time, and a stream that ended whole is given the edit's ending. A stream that broke off ends the
     *  relay there, after the relay's error event when it is read through an edit. */
    private suspend fun pump(answered: Sent.Answered, wire: QuietWire, wait: ClientWait) {
        val edited = wait.edit?.let { EditedWire(wire, it) }
        answered.response.body().use { input ->
            // A read made ahead, to learn that the answer had started, is the first thing relayed.
            val reads = answered.reads ?: AnswerReads(input, io)
            while (true) {
                val count = reads.next(wire).getOrElse { failure ->
                    brokeOff(failure, answered)
                    if (edited != null) wire.error(said(HttpStatus.BAD_GATEWAY, answered.cut))
                    return
                }
                if (count == END_OF_STREAM) {
                    edited?.end()
                    return
                }
                if (edited == null) wire.write(reads.bytes, count) else edited.write(reads.bytes, count)
            }
        }
    }

    /** A read of [answered]'s answer ended in [failure]: the answer broke off, which is said in the log. A relay that
     *  was cancelled fails its read too, and ends here as cancelled. */
    private suspend fun brokeOff(failure: Throwable, answered: Sent.Answered) {
        currentCoroutineContext().ensureActive()
        if (failure !is IOException) throw failure
        log("[${speaker.tag}] ${answered.cut}: ${SafeFailureText.render(failure)}\n")
    }

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

// The one header of a refusal that is for the client whoever refused: how long to wait before asking again.
private const val RETRY_AFTER = "retry-after"
