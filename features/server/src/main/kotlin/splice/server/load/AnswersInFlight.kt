// NEW: server mode (#791, `[server] drain_seconds`). What a stop's drain waits for before it closes the listener: every
// request the listener has read whose answer is not yet all written to its client's connection.
//
// A relay is done when it has handed its answer to the engine, and the engine writes that answer a moment later, on
// the connection's event loop. Closing the listener shuts those loops down, and a loop that is shutting down closes
// every connection it holds before it runs what is queued on it, so a drain that waited for the turns alone could close
// the last turn's connection with its answer still queued: its client read a closed connection and no answer (CI on
// main, Nov 24, 2026: "HTTP/1.1 header parser received no bytes"). Counted at the connection instead, an answer ends
// when the last part of it is on the socket, or when its connection closes.
//
// It is also where a turn's place under the caps (ServerLoad, PersonLimits) is given back in time for its client's next
// turn. Given back only after the relay returned, the place could still be held when a client on a fast link had the
// whole answer and asked again, and that next turn was refused as full though its client never ran two at once. The
// connection gives it back as the answer's last part is handed to the socket, just before it is written: the relay
// has read its upstream to the end by then, so the upstream call the place counts is over, and the client cannot yet
// have the whole answer.
package splice.server.load

import io.ktor.server.application.ApplicationCall
import io.ktor.server.netty.NettyApplicationCall
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPipeline
import io.netty.channel.ChannelPromise
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import splice.server.TurnSlot
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicReference

internal class AnswersInFlight {
    // The listener's answers begun and not yet written, on every connection together.
    private val open = MutableStateFlow(0)

    /** Counts the answers on the connection [pipeline] carries; the listener installs it on every connection. */
    fun watch(pipeline: ChannelPipeline) {
        // Before Ktor's own handler, which writes every answer from its place in the pipeline.
        pipeline.addBefore(KTOR_HTTP1_HANDLER, WATCH_HANDLER, Connection())
    }

    /** Gives [slot] back the moment the last part of [call]'s answer is handed to its connection, before its client
     *  can have it all. A call no watched connection carries gives it back only as its holder does. */
    fun freesAtLastPart(call: ApplicationCall, slot: TurnSlot) {
        val netty = call as? NettyApplicationCall ?: return
        val connection = netty.context.pipeline().get(WATCH_HANDLER) as? Connection ?: return
        connection.newest()?.frees(slot)
    }

    /** Waits up to [withinMs] for every answer begun to be written. Answers whether they all were. */
    suspend fun written(withinMs: Long): Boolean = withTimeoutOrNull(withinMs) { open.first { it == 0 } } != null

    /** How many answers are begun and not yet written now. */
    fun unwritten(): Int = open.value

    /** One request's answer, from the moment it is read until its last part is handed to the socket. */
    private class Answer {
        // The turn's slot this answer gives back, once, as its last part goes out.
        private val held = AtomicReference<TurnSlot?>()

        @Volatile
        private var ended = false

        /** [slot] is given back as this answer's last part goes out, or now if it already has. */
        fun frees(slot: TurnSlot) {
            held.set(slot)
            if (ended) held.getAndSet(null)?.release()
        }

        /** The last part of this answer is being handed to the socket. */
        fun ending() {
            ended = true
            held.getAndSet(null)?.release()
        }
    }

    /** One connection's answers. Netty calls each of these on the connection's own event loop, one at a time. */
    private inner class Connection : ChannelDuplexHandler() {
        // The requests read on this connection whose answer's last part is not yet being written, oldest first:
        // HTTP/1.1 answers them in the order they were asked.
        private val unanswered = ConcurrentLinkedDeque<Answer>()

        /** The answer to the request read last on this connection, while its last part is still to go; asked from a
         *  call's own thread. A call is that request's unless its client asked again before its answer (pipelining):
         *  then it is a later one, whose answer goes out after the call's own, so a slot it frees is never freed
         *  early. */
        fun newest(): Answer? = unanswered.peekLast()

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                unanswered.addLast(Answer())
                open.update { it + 1 }
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val answer = if (msg is LastHttpContent) unanswered.pollFirst() else null
            if (answer == null) {
                ctx.write(msg, promise)
                return
            }
            answer.ending()
            // Written or failed, this answer is no longer the drain's to wait for.
            val done = promise.unvoid()
            done.addListener { open.update { it - 1 } }
            ctx.write(msg, done)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            // A closed connection owes no answer it had not begun to write. Its turns' places are given back by their
            // relays, which see the connection close (WatchedRelay).
            val owed = unanswered.size
            unanswered.clear()
            open.update { it - owed }
            ctx.fireChannelInactive()
        }
    }
}

// The name Ktor's Netty engine gives its HTTP/1.1 handler (NettyChannelInitializer); the listener speaks HTTP/1.1 only.
private const val KTOR_HTTP1_HANDLER = "http1"

private const val WATCH_HANDLER = "splice-answers-in-flight"
