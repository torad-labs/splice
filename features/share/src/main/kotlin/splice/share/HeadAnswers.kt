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
package splice.share

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runInterruptible
import splice.core.util.Cancellables
import splice.core.wire.HttpStatus
import splice.http.SseChannelResponse
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse
import kotlin.time.Duration

/** What a relay tells its client of one request: [silent] when the head does not answer, a keepalive each [every]
 *  while a streamed answer is silent (null: none), and a streamed answer as [edit] reads it (null: byte for byte). */
internal data class ClientWait(val silent: String, val every: Duration? = null, val edit: StreamEdit? = null)

internal class HeadAnswers(
    /** Blocking JDK I/O runs here: each read of the answer. */
    private val io: CoroutineDispatcher,
    private val errors: RefusalVoice,
    /** What a kept-alive client's silences are timed on. */
    private val clock: RelayClock,
) {
    /** [sent] as [call]'s answer: the head's own, or that it is not answering. */
    suspend fun given(call: ApplicationCall, sent: Result<HttpResponse<InputStream>>, wait: ClientWait) {
        val response = sent.getOrElse { return errors.refuse(call, HttpStatus.SERVICE_UNAVAILABLE, wait.silent) }
        response.headers().map().forEach { (name, values) ->
            if (name.lowercase() !in notReturned) values.forEach { call.response.headers.append(name, it) }
        }
        val status = HttpStatusCode.fromValue(response.statusCode())
        val type = typeOf(response)
        if (type.match(ContentType.Text.EventStream)) {
            call.response.status(status)
            call.respond(
                SseChannelResponse { out -> response.body().use { pump(it, quiet(out, wait), wait.edit) } },
            )
        } else {
            val whole = runInterruptible(io) { response.body().use(InputStream::readAllBytes) }
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
                    onSuccess = { response -> late(response, wire) },
                    onFailure = { wire.error(errors.body(HttpStatus.SERVICE_UNAVAILABLE, wait.silent)) },
                )
            },
        )
    }

    /** The head's answer on a stream that is already open: its events, or what it answered in their place. */
    private suspend fun late(response: HttpResponse<InputStream>, wire: QuietWire) {
        if (typeOf(response).match(ContentType.Text.EventStream)) return response.body().use { pump(it, wire) }
        val whole = runInterruptible(io) { response.body().use(InputStream::readAllBytes) }
        wire.error(errors.relayed(response.statusCode(), String(whole)))
    }

    /** The client's side of the stream [out], kept alive as [wait] asks. */
    private fun quiet(out: ByteWriteChannel, wait: ClientWait): QuietWire = QuietWire(out, wait.every, clock)

    private fun typeOf(response: HttpResponse<InputStream>): ContentType =
        response.headers().firstValue("content-type").map { ContentType.parse(it) }
            .orElse(ContentType.Application.OctetStream)

    /** Chunk by chunk, flushed each time: an event (or the head's keepalive ping) reaches the other machine the moment
     *  the head writes it. A client that went away fails the write, and closing the head's stream then hangs up on the
     *  head, which sees its own client leave. A head that hangs up mid-stream ends the relay there. With an [edit] the
     *  chunks go through it, an event at a time, and a stream that ended whole is given the edit's ending. */
    private suspend fun pump(input: InputStream, wire: QuietWire, edit: StreamEdit? = null) {
        val buffer = ByteArray(PUMP_BUFFER_BYTES)
        val edited = edit?.let { EditedWire(wire, it) }
        while (true) {
            val read = wire.during {
                runInterruptible(io) {
                    Cancellables.runCatchingCancellable { input.read(buffer) }
                        .getOrElse { failure -> if (failure is IOException) CUT_SHORT else throw failure }
                }
            }
            if (read == END_OF_STREAM) edited?.end()
            if (read < 0) return
            if (edited == null) wire.write(buffer, read) else edited.write(buffer, read)
        }
    }

    // Framing the response re-chunks, and the type each answer sets itself.
    private val notReturned = setOf("content-length", "transfer-encoding", "connection", "keep-alive", "content-type")
}

// why: one read of the head's answer: an SSE event is a few hundred bytes, so 16 KiB carries a whole burst per flush
// while holding little memory for each relayed stream.
private const val PUMP_BUFFER_BYTES = 16 * 1024

// What InputStream.read answers at the end of the stream.
private const val END_OF_STREAM = -1

// why: a failed read of the answer ends the relay as the end of the stream does, as the head hanging up; it is told
// apart only so that a stream cut short is never given the ending of a whole one.
private const val CUT_SHORT = -2
