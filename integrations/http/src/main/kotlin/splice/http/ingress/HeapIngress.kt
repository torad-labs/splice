// NEW: both daemon listeners share charged ingress and application ownership.
package splice.http.ingress

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.netty.channel.ChannelPipeline
import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import splice.core.config.RequestByteCap
import splice.core.memory.HeapCapacityException
import splice.core.memory.HeapLease
import splice.core.memory.HeapReservations
import splice.core.memory.HeapWeights
import splice.core.wire.HttpStatus
import java.util.concurrent.atomic.AtomicBoolean

private const val CODEC_HANDLER = "codec"
private val connectionKey = io.netty.util.AttributeKey.valueOf<IngressOwnership>("splice.heap.ingress")
private val leaseKey = AttributeKey<HeapLease>("splice.heap.request")

/** The listener supplies its canonical pre-turn HTTP error renderer; transport never builds an SSE error. */
public fun interface IngressErrorBody {
    public fun render(type: String, message: String): String
}

/** Admission runs after the HTTP codec but before automatic continue and Ktor's body actor.
 *  Unknown-length bodies reserve the listener's cap. Refused bodies are never forwarded to Ktor.
 *  A refusal closes its connection after Ktor writes the ordered response, without draining an unlimited body.
 */
public class HeapIngress(
    private val heap: HeapReservations,
    /** The listener's body cap, asked for at every request: a raised cap admits the next one (RequestByteCap). */
    private val maxBodyBytes: RequestByteCap,
    private val errorBody: IngressErrorBody,
    private val requestLimit: Long = heap.limitBytes,
) {
    private val requests = NettyIngressRequest()
    private val stopping = AtomicBoolean()
    private var connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)

    public fun install(pipeline: ChannelPipeline) {
        val ownership = IngressOwnership(heap.reserve(HeapWeights.CONNECTION_BYTES))
        pipeline.channel().attr(connectionKey).set(ownership)
        // A read requested by the decoder also crosses this guard.
        pipeline.addBefore(CODEC_HANDLER, "splice-heap-read", IngressReadGuard(ownership))
        // Order is load-bearing: input shutdown sits before IngressShutdown, so its ctx.close() travels toward the
        // head and never re-enters the staged close.
        pipeline.addBefore(CODEC_HANDLER, "splice-heap-input-shutdown", IngressInputShutdown(ownership))
        pipeline.addBefore(CODEC_HANDLER, "splice-heap-close", IngressShutdown(ownership, stopping))
        pipeline.addBefore(CODEC_HANDLER, "splice-heap-drain", IngressDrain(ownership))
        pipeline.addAfter(
            CODEC_HANDLER,
            "splice-heap-body",
            IngressHandler(heap, maxBodyBytes, requestLimit, ownership),
        )
        // Added after the body handler, so it lands directly behind the codec: the response path sees writes there.
        pipeline.addAfter(CODEC_HANDLER, "splice-heap-response", IngressResponseWatch(ownership))
        // Physical close settles ownership even when a retired registration emits no channelInactive.
        pipeline.channel().closeFuture().addListener { ownership.disconnect() }
        val _ = connections.add(pipeline.channel())
    }

    /** Bind the original request identity before routing wraps it. No injected header crosses the wire. */
    public fun install(application: Application) {
        stopping.set(false)
        connections = DefaultChannelGroup(GlobalEventExecutor.INSTANCE, true)
        // Stop accepted sockets while their event loops still run. Shutdown cancels scheduled drain tasks.
        application.monitor.subscribe(ApplicationStopPreparing) {
            stopping.set(true)
            connections.close().syncUninterruptibly()
        }
        application.intercept(ApplicationCallPipeline.Monitoring) {
            val request = requests.request(call)
            val ownership = requests.channel(call)?.attr(connectionKey)?.get()
            val entry = request?.let { ownership?.bind(it) }
            if (entry == null) {
                proceed()
            } else {
                entry.lease?.let { call.attributes.put(leaseKey, it) }
                try {
                    if (entry.refusal == null) {
                        proceed()
                    } else {
                        refuse(call, entry.refusal, entry.refusalMessage)
                        finish()
                    }
                } catch (capacity: HeapCapacityException) {
                    if (call.response.isCommitted) throw capacity
                    refuse(call, HttpStatus.OVERLOADED)
                    finish()
                } finally {
                    // Settle unread input and suppress every future decoder read before returning its charge.
                    val input = call.request.receiveChannel()
                    val owner = checkNotNull(ownership)
                    if (!input.isClosedForRead) owner.unread(entry)
                    input.cancel(null)
                    if (owner.finish(entry)) requests.channel(call)?.close()
                }
            }
        }
    }

    private suspend fun refuse(call: ApplicationCall, status: Int, detail: String? = null) {
        val large = status == HttpStatus.CONTENT_TOO_LARGE
        val message = detail ?: if (large) "request body exceeds ${maxBodyBytes()} bytes" else "gateway busy; retry"
        val type = if (large) "invalid_request_error" else "overloaded_error"
        call.respondText(
            errorBody.render(type, message),
            ContentType.Application.Json,
            HttpStatusCode(status, if (large) "Content Too Large" else "Gateway At Capacity"),
        )
    }
}

/** The bridge from a routed call to the ingress charge bound before its request was wrapped. */
public object IngressLeases {
    public fun borrow(call: ApplicationCall): HeapLease? = call.attributes.getOrNull(leaseKey)?.share()
}
