// NEW: V4-126 FEATURES.md §6 — GET /api/events, the console's server-sent stream.
//
// ADDITIVE BY CONSTRUCTION: no existing poll route changes. The stream exists so a console gesture
// fires when something happens instead of on the next poll, and the poll routes stay as the
// fallback — which is also what makes the drop policy safe, since a client that fell behind can
// always re-read the truth from a poll rather than reconstruct it from a lossy stream.
//
// Everything protocol-shaped lives in EventBus (the families, the ids, the ring). This file only
// speaks ktor, and it mirrors McpRoutes.stream's shape on purpose: same writer, same select over
// the channel with a timeout, same flush per frame. The one thing it adds is the resume header.
package splice.control.api

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.Json
import splice.control.api.diagnostics.SseWrite

/** A comment frame on this cadence keeps the connection honest while nothing is happening: a
 *  console that hears nothing cannot tell a quiet daemon from a dead socket. */
private const val HEARTBEAT_MS = 15_000L

/** The reconnect header the console sends. A REQUEST header, not a query parameter, so the bearer
 *  stays the only thing in the URL. */
private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"

internal class EventsRoute(private val bus: EventBus) {
    private val json = Json

    suspend fun stream(call: ApplicationCall) {
        // A malformed id starts the stream from now rather than failing it: the console's recovery
        // from a bad id is the same as its recovery from no id, and refusing would leave it with a
        // dead stream and a poll fallback it did not need to fall back to.
        val lastEventId = call.request.header(LAST_EVENT_ID_HEADER)?.trim()?.toLongOrNull()
        val subscription = bus.subscribe(lastEventId)
        try {
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8(": open\n\n")
                flush()
                pump(subscription.channel) { frame ->
                    writeStringUtf8(frame)
                    flush()
                }
            }
        } finally {
            // The client went away (or the daemon is stopping): release the subscriber, or the bus
            // keeps filling a channel nobody reads for the life of the process.
            bus.unsubscribe(subscription)
        }
    }

    /** Frames until the subscriber closes. The timeout is not an idle timer that ends the stream —
     *  it writes a heartbeat comment and goes straight back to waiting, which is what `select`
     *  falling through to the next loop iteration gives us. */
    private suspend fun pump(channel: ReceiveChannel<ConsoleEvent>, write: SseWrite) {
        while (true) {
            val frame = select<String?> {
                channel.onReceiveCatching { result -> result.getOrNull()?.let(::frame) ?: "" }
                onTimeout(HEARTBEAT_MS) { ": heartbeat\n\n" }
            }
            if (frame.isNullOrEmpty()) return
            write(frame)
        }
    }

    /** One SSE frame. `id` is the seq the client echoes back as Last-Event-ID; `event` is the kind
     *  the console switches on; `data` is one JSON object with no type discriminator in it. */
    private fun frame(event: ConsoleEvent): String =
        "id: ${event.seq}\nevent: ${event.kind}\ndata: ${event.encodeTo(json)}\n\n"
}
