// NEW: the client's half of a relay (docs/specs/lan-share.md; docs/specs/server-mode.md, F4). A head's answer, given
// to the client that asked: an event stream goes back through SseChannelResponse, the daemon's one streaming writer,
// chunk by chunk as the head writes it, and any other answer goes back whole, under the head's own status.
//
// Server mode keeps a streamed turn's client alive while its answer is silent (QuietWire). A model server prefilling
// a long prompt keeps the head silent before its first byte, so a streamed turn whose answer has not started in a
// second has its stream opened to the client then, a keepalive written at once and at each interval, and the head's
// events after them when they come. An answer has started when the first byte of it has come, or when it came and
// is no stream: response headers alone start nothing, because one server holds them back through its prefill and
// another sends them at once and prefills after them (AnswerBody), and the client of either hears nothing until
// something is written to it. The status has gone out as 200 by then, so a head that refuses such a turn late, or
// does not answer it, is said as the stream's `error` event, in the head's own words where it gave them, and the
// status it stands for is kept beside the call for whoever records how the request went (AnsweredStatus). A head
// whose answer starts within the second answers as it always did, its own status included. The second and the
// interval are waited out on the relay's clock (RelayClock). No thread waits on an answer through any of it, so the
// number of turns that wait on their model servers at once is no limit on any of them (AnswerBody, RelayClient).
//
// A relay that answers for a provider's own server (F4, openai-route) reads that server's stream through a StreamEdit,
// event by event, and gives it its ending only when the server ended it whole. An answer of that server's that is no
// stream may be read too, through a WholeEdit, when its client asked for less than the server answers. A streamed
// request to such a server is kept alive the same way a head's turn is (F4, openai-keepalive), and what ends a stream
// that was opened before its server answered is the error event its client's SDK reads (RefusalVoice.event).
//
// With `[server] stream_idle_seconds` (#791), a streamed answer that falls silent that long after its first word is
// hung up on and ends with the relay's error event, the way a broken one does; before its first word it is waited on
// however long, as a prefill is (StreamIdle).
//
// An answer that breaks off before its end is never passed on as if it had ended (F4, "an answer the upstream cuts
// short is an error the client sees"). One that is no stream is read whole before any of it is given, so a body that
// breaks off is a 502 in the relay's own words. A stream is passed on event by event, its bytes as the server wrote
// them, so its client's stream stands between two events when the server's breaks: it ends with the relay's error
// event and never with the ending of a whole one. Each is said in the log, and a relay that was cancelled broke nothing
// off.
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import splice.core.util.LogSink
import splice.core.util.SafeFailureText
import splice.core.wire.HttpStatus
import splice.http.SseChannelResponse
import splice.upstream.failure.ReplyFacts.RETRY_AFTER_HEADER
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** What a relay tells its client of one request: a keepalive each [every] while a streamed answer is silent (null:
 *  none), and a streamed answer as [edit] reads it (null: byte for byte), or one that is no stream as [whole] does
 *  (null: as it is). [outside] says the answer is a server's that is no part of this machine, whose own headers stay
 *  here. A streamed answer silent for [idleMs] after its first word is ended with an error event (StreamIdle; 0: it is
 *  waited on however long). */
internal data class ClientWait(
    val every: Duration? = null,
    val edit: StreamEdit? = null,
    val whole: WholeEdit? = null,
    val outside: Boolean = false,
    val idleMs: Long = 0L,
)

/** How asking for an answer ended. */
internal sealed class Sent {
    /** [who] gave [response]; [who] is how the client hears that one named, should the answer break off. */
    class Answered(val response: HttpResponse<AnswerBody>, val who: String) : Sent() {
        /** What the client hears when [who] broke its answer off before it ended. */
        val cut: String get() = "$who broke off its answer before it ended"
    }

    /** No one gave an answer, and the client hears [why]: who it is that is not answering. */
    class Silent(val why: String) : Sent()
}

internal class HeadAnswers(
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
        call.respond(SseChannelResponse { out -> pump(call, answered, quiet(out, wait), wait) })
    }

    /** [sent], back once its answer has started: an event stream when its first bytes have come, or it has ended or
     *  broken off without any, and whatever else [sent] is, as it stands. A relay that keeps its client alive waits
     *  on this, so a server that sent its response headers and then fell silent is still one that has not answered. */
    suspend fun started(sent: Sent): Sent {
        if (sent is Sent.Answered && typeOf(sent.response).match(ContentType.Text.EventStream)) {
            sent.response.body().begin()
        }
        return sent
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
                    is Sent.Answered -> late(call, sent, wire, wait)
                    is Sent.Silent -> {
                        AnsweredStatus.late(call, HttpStatus.SERVICE_UNAVAILABLE)
                        wire.error(said(HttpStatus.SERVICE_UNAVAILABLE, sent.why))
                    }
                }
            },
        )
    }

    /** An answer on a stream that is already open: its events, or what was answered in their place, which [call]
     *  remembers the status of (AnsweredStatus): its own went out as 200 before anyone had answered. */
    private suspend fun late(call: ApplicationCall, answered: Sent.Answered, wire: QuietWire, wait: ClientWait) {
        val response = answered.response
        if (typeOf(response).match(ContentType.Text.EventStream)) return pump(call, answered, wire, wait)
        val whole = whole(answered)
        AnsweredStatus.late(call, if (whole == null) HttpStatus.BAD_GATEWAY else response.statusCode())
        if (whole == null) return wire.error(said(HttpStatus.BAD_GATEWAY, answered.cut))
        wire.error(errors.event(errors.relayed(response.statusCode(), String(whole), answered.who)))
    }

    /** The headers of [response] that go back with it. A head's all do, but the ones that frame it. Of a server's
     *  that is outside this machine, only `Retry-After`, and only when it refused. */
    private fun returned(call: ApplicationCall, response: HttpResponse<AnswerBody>, wait: ClientWait) {
        val refused = response.statusCode() >= HttpStatus.BAD_REQUEST
        response.headers().map().forEach { (name, values) ->
            val back = name.lowercase().let {
                if (wait.outside) refused && it == RETRY_AFTER_HEADER else it !in notReturned
            }
            if (back) values.forEach { call.response.headers.append(name, it) }
        }
    }

    /** The client's side of the stream [out], kept alive as [wait] asks. */
    private fun quiet(out: ByteWriteChannel, wait: ClientWait): QuietWire = QuietWire(out, wait.every, clock)

    /** [message] under [status], as the event that says it on a stream already open. */
    private fun said(status: Int, message: String): String = errors.event(errors.body(status, message))

    private fun typeOf(response: HttpResponse<AnswerBody>): ContentType =
        response.headers().firstValue("content-type").map { ContentType.parse(it) }
            .orElse(ContentType.Application.OctetStream)

    /** The whole of an answer that is no stream, or null when it broke off before its end. */
    private suspend fun whole(answered: Sent.Answered): ByteArray? =
        answered.response.body().use { it.whole() }.getOrElse { failure -> null.also { brokeOff(failure, answered) } }

    /** [answered]'s stream, event by event, flushed each time: an event (or the head's keepalive ping) reaches the
     *  other machine the moment its blank line is read (QuietWire). A client that went away fails the write, and
     *  closing the body then hangs up on the one that answered, which sees its own client leave. With an edit the
     *  events go through it, and a stream that ended whole is given the edit's ending. A stream that broke off ends
     *  the relay there, after the relay's error event. One silent for [ClientWait.idleMs] after its first word is hung
     *  up on, and ends with the relay's error event ([idled]). */
    private suspend fun pump(call: ApplicationCall, answered: Sent.Answered, wire: QuietWire, wait: ClientWait) {
        val edited = wait.edit?.let { EditedWire(wire, it) }
        answered.response.body().use { body ->
            coroutineScope {
                val idle = StreamIdle(wait.idleMs, clock, this)
                try {
                    while (true) {
                        val next = wire.during { idle.within { body.next() } }
                            ?: return@coroutineScope idled(call, answered, wire, wait.idleMs)
                        val piece = next.getOrElse { failure ->
                            brokeOff(failure, answered)
                            wire.error(said(HttpStatus.BAD_GATEWAY, answered.cut))
                            return@coroutineScope
                        }
                        if (piece == null) return@coroutineScope edited?.end() ?: wire.end()
                        idle.heard(piece)
                        if (edited == null) wire.write(piece, piece.size) else edited.write(piece, piece.size)
                    }
                } finally {
                    idle.stop()
                }
            }
        }
    }

    /** [answered]'s stream fell silent for [idleMs] after its answer began (`[server] stream_idle_seconds`): its server
     *  is hung up on as the body closes, and its client hears why as the stream's error event, which Claude Code and
     *  the OpenAI SDKs raise and retry, never as an ending. The ledger reads the turn as a 504. An event the server
     *  left half written never reached the client (QuietWire), so the error is always one its client reads. */
    private suspend fun idled(call: ApplicationCall, answered: Sent.Answered, wire: QuietWire, idleMs: Long) {
        val seconds = TimeUnit.MILLISECONDS.toSeconds(idleMs)
        val why = "${answered.who} said nothing for ${seconds}s after its answer began (stream_idle_seconds = " +
            "$seconds), so the turn was ended; send it again"
        log("[${speaker.tag}] $why\n")
        AnsweredStatus.late(call, HttpStatus.GATEWAY_TIMEOUT)
        wire.error(said(HttpStatus.GATEWAY_TIMEOUT, why))
    }

    /** [answered]'s answer broke off before its end, with [failure], which is said in the log. */
    private fun brokeOff(failure: Throwable, answered: Sent.Answered) {
        log("[${speaker.tag}] ${answered.cut}: ${SafeFailureText.render(failure)}\n")
    }

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}
