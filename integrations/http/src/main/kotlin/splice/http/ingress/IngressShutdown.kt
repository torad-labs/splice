// NEW: a completed early response closes in stages so unread input cannot erase its HTTP reply.
package splice.http.ingress

import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.socket.ChannelInputShutdownEvent
import io.netty.channel.socket.DuplexChannel
import io.netty.channel.socket.SocketChannelConfig
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// why: RFC 9112 section 9.6 describes staged close; bound the final acknowledgement/drain interval to one second.
private const val SHUTDOWN_GRACE_MS = 1000L

/** After the final response flush, half-close output and discard raw input through the charged connection. */
internal class IngressShutdown(
    private val ownership: IngressOwnership,
    private val stopping: AtomicBoolean,
) : ChannelDuplexHandler() {
    private var deadline: ScheduledFuture<*>? = null

    override fun close(ctx: ChannelHandlerContext, promise: ChannelPromise) {
        val socket = ctx.channel() as? DuplexChannel
        val stage = ownership.stageClose && !stopping.get()
        if (socket == null || !stage) {
            ctx.close(promise)
            return
        }
        ctx.channel().closeFuture().addListener { val _ = promise.trySuccess() }
        if (!ownership.draining.compareAndSet(false, true)) return
        val _ = (socket.config() as? SocketChannelConfig)?.setAllowHalfClosure(true)
        socket.shutdownOutput().addListener { result ->
            if (result.isSuccess) ctx.read() else ctx.close()
        }
        deadline = ctx.executor().schedule({ ctx.close() }, SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (ownership.draining.get()) ReferenceCountUtil.release(msg) else ctx.fireChannelRead(msg)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (ownership.draining.get()) ctx.read() else ctx.fireChannelReadComplete()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, event: Any) {
        if (ownership.draining.get() && event is ChannelInputShutdownEvent) {
            ctx.close()
        } else {
            ctx.fireUserEventTriggered(event)
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val _ = deadline?.cancel(false)
        ctx.fireChannelInactive()
    }
}
