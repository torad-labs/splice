// NEW: a completed early response closes in stages so unread input cannot erase its HTTP reply.
package splice.http.ingress

import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.SimpleUserEventChannelHandler
import io.netty.channel.socket.ChannelInputShutdownEvent
import io.netty.channel.socket.DuplexChannel
import io.netty.channel.socket.SocketChannelConfig
import io.netty.util.ReferenceCountUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// why: RFC 9112 section 9.6 describes staged close; bound the final acknowledgement/drain interval to one second.
internal const val SHUTDOWN_GRACE_MS = 1000L

// why: a client still sending its body after the reply keeps the drain open, each read restarting the grace, for this
// long at most from the staged close, so a trickling client cannot hold the connection.
internal const val SHUTDOWN_CEILING_MS = 10_000L

/**
 * After the final response flush, half-close output through the charged connection. Its input halves sit on
 * either side of it: [IngressInputShutdown] before it, [IngressDrain] after it.
 *
 * The grace is counted from the client's last bytes, not from the half-close. A fixed second closed on a client still
 * streaming the body it had sent before its 529 came back, and a close with unread input is a reset, which takes the
 * reply the client had not read yet with it (RequestHeapBudgetTest, ad71cc142: one of 139 answers lost after a pause).
 */
internal class IngressShutdown(
    private val ownership: IngressOwnership,
    private val stopping: AtomicBoolean,
    private val graceMs: Long = SHUTDOWN_GRACE_MS,
) : ChannelDuplexHandler() {
    private var deadline: ScheduledFuture<*>? = null
    private var closeByNanos = 0L

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
        closeByNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_CEILING_MS)
        deadline = ctx.executor().schedule({ ctx.close() }, graceMs, TimeUnit.MILLISECONDS)
    }

    /** Input while draining is a client still sending: its grace starts again, up to the ceiling. */
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (ownership.draining.get() && deadline?.cancel(false) == true) {
            val left = TimeUnit.NANOSECONDS.toMillis(closeByNanos - System.nanoTime()).coerceAtLeast(0L)
            deadline = ctx.executor().schedule({ ctx.close() }, minOf(graceMs, left), TimeUnit.MILLISECONDS)
        }
        ctx.fireChannelRead(msg)
    }

    override fun channelReadComplete(ctx: ChannelHandlerContext) {
        if (ownership.draining.get()) ctx.read() else ctx.fireChannelReadComplete()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        val _ = deadline?.cancel(false)
        ctx.fireChannelInactive()
    }
}

/** Drops raw input while the connection drains. autoRelease is off: a dropped buffer is released here, once. */
internal class IngressDrain(private val ownership: IngressOwnership) : SimpleChannelInboundHandler<ByteBuf>(false) {
    override fun channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        if (ownership.draining.get()) {
            ReferenceCountUtil.release(msg)
        } else {
            ctx.fireChannelRead(msg)
        }
    }
}

/** A peer's input shutdown closes a draining connection; every other user event passes through unchanged. */
internal class IngressInputShutdown(private val ownership: IngressOwnership) :
    SimpleUserEventChannelHandler<ChannelInputShutdownEvent>(false) {
    override fun eventReceived(ctx: ChannelHandlerContext, event: ChannelInputShutdownEvent) {
        if (ownership.draining.get()) {
            ctx.close()
        } else {
            ctx.fireUserEventTriggered(event)
        }
    }
}
