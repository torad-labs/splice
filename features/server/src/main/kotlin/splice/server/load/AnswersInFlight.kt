// NEW: server mode (#791, `[server] drain_seconds`). What a stop's drain waits for before it closes the listener: every
// request the listener has read whose answer is not yet all written to its client's connection.
//
// A turn's own place (ServerLoad) is given back when its relay has handed its answer to the engine, and the engine
// writes that answer a moment later, on the connection's event loop. Closing the listener shuts those loops down, and a
// loop that is shutting down closes every connection it holds before it runs what is queued on it, so a drain that
// waited for the turns alone could close the last turn's connection with its answer still queued: its client read a
// closed connection and no answer (CI on main, Nov 24, 2026: "HTTP/1.1 header parser received no bytes"). Counted at
// the connection instead, an answer ends when the last part of it is on the socket, or when its connection closes.
package splice.server.load

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

internal class AnswersInFlight {
    // The listener's answers begun and not yet written, on every connection together.
    private val open = MutableStateFlow(0)

    /** Counts the answers on the connection [pipeline] carries; the listener installs it on every connection. */
    fun watch(pipeline: ChannelPipeline) {
        // Before Ktor's own handler, which writes every answer from its place in the pipeline.
        pipeline.addBefore(KTOR_HTTP1_HANDLER, WATCH_HANDLER, Connection())
    }

    /** Waits up to [withinMs] for every answer begun to be written. Answers whether they all were. */
    suspend fun written(withinMs: Long): Boolean = withTimeoutOrNull(withinMs) { open.first { it == 0 } } != null

    /** How many answers are begun and not yet written now. */
    fun unwritten(): Int = open.value

    /** One connection's answers. Netty calls each of these on the connection's own event loop, one at a time. */
    private inner class Connection : ChannelDuplexHandler() {
        // The requests read on this connection whose answer's last part is not yet being written.
        private var unanswered = 0

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                unanswered += 1
                open.update { it + 1 }
            }
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg !is LastHttpContent || unanswered == 0) {
                ctx.write(msg, promise)
                return
            }
            unanswered -= 1
            // Written or failed, this answer is no longer the drain's to wait for.
            val done = promise.unvoid()
            done.addListener { open.update { it - 1 } }
            ctx.write(msg, done)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            // A closed connection owes no answer it had not begun to write.
            open.update { it - unanswered }
            unanswered = 0
            ctx.fireChannelInactive()
        }
    }
}

// The name Ktor's Netty engine gives its HTTP/1.1 handler (NettyChannelInitializer); the listener speaks HTTP/1.1 only.
private const val KTOR_HTTP1_HANDLER = "http1"

private const val WATCH_HANDLER = "splice-answers-in-flight"
