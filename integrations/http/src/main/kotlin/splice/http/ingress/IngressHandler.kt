// NEW: reserve before Ktor copies a body, with an ordered empty refusal when capacity is absent.
package splice.http.ingress

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObject
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
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
    private val errorBody: IngressErrorBody? = null,
) : SimpleChannelInboundHandler<HttpObject>(false) {
    private var silence: ScheduledFuture<*>? = null
    private var growing: ChunkedBody? = null

    /** The admitted request whose body is still arriving; its last chunk marks it arrived. */
    private var reading: IngressRequest? = null
    private var admittedCap = 0L

    override fun channelRead0(ctx: ChannelHandlerContext, msg: HttpObject) {
        if (ownership.halted.get()) {
            ReferenceCountUtil.release(msg)
            return
        }
        if (msg !is HttpRequest) {
            forwardBody(ctx, msg)
            return
        }
        growing = null
        reading = null
        val entry = admit(msg)
        if (entry.refusal == null) {
            reading = entry
            if (msg is LastHttpContent) entry.arrived = true
            entry.small = HttpUtil.getContentLength(msg, -1L) in 0L..SMALL_BODY_BYTES
            ownership.register(msg, entry)
            if (entry.lease != null && isUndeclared(msg)) growing = ChunkedBody(entry, admittedCap)
            ctx.fireChannelRead(msg)
            watchBody(ctx, msg)
        } else {
            refuse(ctx, msg, entry)
        }
    }

    /** A chunk of an admitted request: charged first when its length was never declared, then handed to Ktor. */
    private fun forwardBody(ctx: ChannelHandlerContext, msg: HttpObject) {
        val live = growing
        val refusal = if (live != null && msg is HttpContent) live.receive(msg.content().readableBytes()) else null
        if (live != null && refusal != null) {
            refuseMidBody(ctx, msg, live, refusal)
            return
        }
        if (msg is LastHttpContent) {
            growing = null
            reading?.arrived = true
            reading = null
        }
        ctx.fireChannelRead(msg)
        watchBody(ctx, msg)
    }

    private fun admit(request: HttpRequest): IngressRequest {
        // ONE reading of each for this request, so the cap that sizes an unknown-length body is the same cap it is
        // then judged against even if an operator changes the knob between the two.
        val capBytes = cap().toLong()
        admittedCap = capBytes
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
        return if (HttpUtil.isTransferEncodingChunked(request)) minOf(CHUNKED_FLOOR_BYTES, capBytes) else 0L
    }

    private fun isUndeclared(request: HttpRequest): Boolean =
        HttpUtil.getContentLength(request, -1L) < 0L && HttpUtil.isTransferEncodingChunked(request)

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
     * A chunked body that outgrew its cap or the heap after it was admitted: answer it here when this is the only
     * reply the connection owes, so the reply cannot jump an older one, and close either way. The charge returns
     * through the disconnect that close causes.
     */
    private fun refuseMidBody(ctx: ChannelHandlerContext, chunk: HttpObject, body: ChunkedBody, why: BodyRefusal) {
        ReferenceCountUtil.release(chunk)
        ownership.halted.set(true)
        growing = null
        silence?.cancel(false)
        val render = errorBody
        if (render == null || !ownership.soleOutstanding(body.entry)) {
            ctx.close()
            return
        }
        val large = why.status == HttpStatus.CONTENT_TOO_LARGE
        val text = render.render(if (large) "invalid_request_error" else "overloaded_error", why.message)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reason = if (large) "Content Too Large" else "Gateway At Capacity"
        val reply = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus(why.status, reason),
            Unpooled.wrappedBuffer(bytes),
        )
        reply.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json")
        reply.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.size)
        HttpUtil.setKeepAlive(reply, false)
        ctx.writeAndFlush(reply).addListener(ChannelFutureListener.CLOSE)
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
        val interim = msg is HttpResponse && msg.status().code() < HttpStatus.OK
        // The final reply's headers say whether the connection lives on, so they must agree with the close below.
        if (msg is HttpResponse && !interim) announce(msg)
        if (msg is LastHttpContent && !interim) {
            promise.addListener { if (ownership.responded()) ctx.close() }
        }
        ctx.write(msg, promise)
    }

    private fun announce(headers: HttpResponse) {
        if (ownership.announceClose()) HttpUtil.setKeepAlive(headers, false)
    }
}
