// NEW: reserve before Ktor copies a body, with an ordered empty refusal when capacity is absent.
package splice.http.ingress

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.ReferenceCountUtil
import splice.core.config.IngressReadIdleMs
import splice.core.config.MaterializedByteCap
import splice.core.config.RequestByteCap
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.wire.HttpStatus
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal class IngressReadGuard(private val ownership: IngressOwnership) : ChannelOutboundHandlerAdapter() {
    override fun read(ctx: ChannelHandlerContext) {
        if (!ownership.halted.get() || ownership.draining.get()) ctx.read()
    }
}

internal class IngressHandler(
    private val heap: HeapReservations,
    /** Read once per admitted request, not once per connection: this handler outlives a cap change. */
    private val cap: RequestByteCap,
    /** Read once per admitted request too: the gate behind this guard derives it from a live knob. */
    private val requestLimit: MaterializedByteCap,
    private val ownership: IngressOwnership,
    /** Asked for at every chunk; null means no bound here. */
    private val readIdleMs: IngressReadIdleMs? = null,
) : SimpleChannelInboundHandler<HttpObject>(false) {
    private var silence: ScheduledFuture<*>? = null

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (ownership.halted.get()) {
            ReferenceCountUtil.release(msg)
            return
        }
        if (msg !is HttpRequest) {
            ctx.fireChannelRead(msg)
            watchBody(ctx, msg)
            return
        }
        val entry = admit(msg)
        if (entry.refusal == null) {
            ownership.register(msg, entry)
            ctx.fireChannelRead(msg)
            watchBody(ctx, msg)
        } else {
            refuse(ctx, msg, entry)
        }
    }

    private fun admit(request: HttpRequest): IngressRequest {
        // ONE reading of each for this request, so the cap that sizes an unknown-length body is the same cap it is
        // then judged against even if an operator changes the knob between the two.
        val capBytes = cap().toLong()
        val limitBytes = requestLimit()
        val bytes = bodyBytes(request, capBytes)
        val bodyWeight = HeapWeights.request(bytes)
        val weight = HeapWeights.ingress(bytes)
        val capacity = (heap.limitBytes - HeapWeights.CONNECTION_BYTES).coerceAtLeast(0L)
        return when {
            bytes > capBytes -> IngressRequest(null, HttpStatus.CONTENT_TOO_LARGE)
            bodyWeight > limitBytes -> IngressRequest(
                null,
                HttpStatus.CONTENT_TOO_LARGE,
                "request body requires $bodyWeight bytes; materialization heap limit is $limitBytes bytes",
            )
            weight > capacity -> IngressRequest(
                null,
                HttpStatus.CONTENT_TOO_LARGE,
                "request ingress requires $weight bytes plus a connection; " +
                    "process heap limit is ${heap.limitBytes} bytes",
            )
            else -> {
                val lease = if (ownership.admitted) heap.reserve(weight) else null
                IngressRequest(lease, if (lease == null) HttpStatus.OVERLOADED else null)
            }
        }
    }

    private fun bodyBytes(request: HttpRequest, capBytes: Long): Long {
        val declared = HttpUtil.getContentLength(request, -1L)
        if (declared >= 0L) return declared
        return if (HttpUtil.isTransferEncodingChunked(request)) capBytes else 0L
    }

    private fun refuse(ctx: ChannelHandlerContext, request: HttpRequest, entry: IngressRequest) {
        ownership.halted.set(true)
        // Never mutate the decoder's original message or publish a raw response ahead of an older SSE.
        // Ktor receives an empty call and keeps its existing pipelined response ordering.
        val empty = DefaultHttpRequest(request.protocolVersion(), request.method(), request.uri())
        empty.headers().set(request.headers())
        empty.headers().remove(HttpHeaderNames.EXPECT)
        empty.headers().remove(HttpHeaderNames.TRANSFER_ENCODING)
        HttpUtil.setContentLength(empty, 0L)
        HttpUtil.setKeepAlive(empty, false)
        ownership.register(empty, entry)
        ReferenceCountUtil.release(request)
        ctx.fireChannelRead(empty)
        ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT)
    }

    /**
     * Armed at a request's headers, restarted by every chunk, stopped by its last: a body that goes quiet holds its
     * ingress charge until the bound passes, then the connection closes and the charge returns through the same
     * disconnect a client's own close takes. A halted connection is already closing in stages and keeps that path.
     */
    private fun watchBody(ctx: ChannelHandlerContext, msg: HttpObject) {
        val bound = readIdleMs ?: return
        silence?.cancel(false)
        silence = null
        if (msg is LastHttpContent) return
        silence = ctx.executor().schedule(
            { if (!ownership.halted.get()) ctx.close() },
            bound(),
            TimeUnit.MILLISECONDS,
        )
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        silence?.cancel(false)
        ownership.disconnect()
        ctx.fireChannelInactive()
    }
}

/** The response path: a final response that ends a connection's last request closes it once its write completes. */
internal class IngressResponseWatch(private val ownership: IngressOwnership) : ChannelOutboundHandlerAdapter() {
    override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
        val interim = msg is FullHttpResponse && msg.status().code() < HttpStatus.OK
        if (msg is LastHttpContent && !interim) {
            promise.addListener { if (ownership.responded()) ctx.close() }
        }
        ctx.write(msg, promise)
    }
}
