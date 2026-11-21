// NEW 2026-11-19 (reply-stream): GET /api/sessions/{id}/live on the control plane, behind the management key as every
// /api route is (the mount guards it). It answers text/event-stream at once with `hello`, then every event of the
// session's turns, and stays open until the client leaves: never because a turn ended. Read-only: it reads nothing
// from the request and nothing reaches a turn through it.
package splice.head.live

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonWire
import splice.http.JsonReply
import splice.http.SseChannelResponse

private const val LIVE_REPLIES_UNWIRED = "live replies are not wired on this daemon"

/** The hub the route reads, at call time: the daemon wires it after the control plane is built. */
public fun interface LiveRepliesSource {
    public operator fun invoke(): LiveReplies?
}

/** Follows one session's live reply for one client. */
public class LiveRoute(private val replies: LiveRepliesSource) {
    /** Answers [call] with [session]'s live reply until the client leaves, or until it falls so far behind that its
     *  response is ended (it can connect again for the snapshot). */
    public suspend fun follow(call: ApplicationCall, session: String) {
        val hub = replies()
        if (hub == null) {
            val body = JsonWire.string(buildJsonObject { put("error", LIVE_REPLIES_UNWIRED) })
            JsonReply(HttpStatusCode.ServiceUnavailable, body).send(call)
            return
        }
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.respond(
            SseChannelResponse { out ->
                // A follower that overflowed is ended here: its writer is cancelled, a socket write it is suspended
                // in included, and what was queued for it is never drained into the socket.
                val writer = currentCoroutineContext().job
                val follow = hub.follow(session) { writer.cancel() }
                try {
                    write(out, follow.hello)
                    pump(follow.events, out)
                } finally {
                    follow.leave()
                }
            },
        )
    }

    /** Every event as it comes, and the ping on a stream quiet for [LIVE_PING_AFTER_MS]; returns when the events
     *  close. */
    // select's onTimeout is the ping; kotlinx.coroutines still marks it experimental.
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun pump(events: ReceiveChannel<String>, out: ByteWriteChannel) {
        while (true) {
            val next = select<String?> {
                events.onReceiveCatching { it.getOrNull() }
                onTimeout(LIVE_PING_AFTER_MS) { LIVE_PING }
            } ?: return
            write(out, next)
        }
    }

    private suspend fun write(out: ByteWriteChannel, text: String) {
        out.writeStringUtf8(text)
        out.flush()
    }
}
