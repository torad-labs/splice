package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import splice.codemode.nativehost.NATIVE_MAX_FRAME_BYTES
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// why: Codex core/code_mode_host.rs detects an unresponsive host after a 30 s idle ping plus a 10 s timeout.
private const val NATIVE_GRPC_KEEP_ALIVE_SECONDS = 30L
private const val NATIVE_GRPC_KEEP_ALIVE_TIMEOUT_SECONDS = 10L

/** Real gRPC streams retain independent flow control, status, metadata and explicit cancellation. */
internal class NativeGrpcChannel(
    process: Process,
    scope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    onFailure: NativeGrpcTransportFailure,
) : AutoCloseable {
    private val pipe = NativeGrpcPipe(process, scope, ioDispatcher, onFailure)
    private val channel = NettyChannelBuilder.forAddress(pipe.address)
        .usePlaintext()
        .disableRetry()
        .keepAliveTime(NATIVE_GRPC_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
        .keepAliveTimeout(NATIVE_GRPC_KEEP_ALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .keepAliveWithoutCalls(true)
        .maxInboundMessageSize(NATIVE_MAX_FRAME_BYTES)
        .build()

    fun start(): Unit = pipe.start()

    fun stream(request: NativeGrpcRequest, listener: NativeGrpcListener): NativeGrpcCall {
        val options = request.timeoutMillis?.let { CallOptions.DEFAULT.withDeadlineAfter(it, TimeUnit.MILLISECONDS) }
            ?: CallOptions.DEFAULT
        val call = channel.newCall(NativeGrpcWire.method(request.method), options)
        val headers = Metadata()
        request.route?.let {
            headers.put(
                Metadata.Key.of(NATIVE_GRPC_ROUTE_HEADER, Metadata.ASCII_STRING_MARSHALLER),
                it,
            )
        }
        request.traceparent?.let {
            headers.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), it)
        }
        call.start(
            object : ClientCall.Listener<DynamicMessage>() {
                override fun onHeaders(headers: Metadata): Unit = listener.onHeaders(headers)
                override fun onMessage(message: DynamicMessage) {
                    listener.onMessage(message)
                    call.request(1)
                }
                override fun onClose(status: Status, trailers: Metadata): Unit = listener.onClose(status, trailers)
            },
            headers,
        )
        call.request(1)
        call.sendMessage(request.message)
        call.halfClose()
        return NativeGrpcCall(call)
    }

    suspend fun unary(request: NativeGrpcRequest): NativeGrpcReply = suspendCancellableCoroutine { continuation ->
        var response: DynamicMessage? = null
        var responseHeaders = Metadata()
        val call = stream(
            request,
            object : NativeGrpcListener {
                override fun onHeaders(headers: Metadata) {
                    responseHeaders = headers
                }
                override fun onMessage(message: DynamicMessage) {
                    response = message
                }
                override fun onClose(status: Status, trailers: Metadata) {
                    if (!continuation.isActive) return
                    val message = response
                    if (status.isOk && message != null) {
                        continuation.resume(NativeGrpcReply(message, responseHeaders, trailers))
                    } else {
                        val failure = if (status.isOk) {
                            Status.INTERNAL.withDescription(
                                "Native gRPC unary response was empty",
                            )
                        } else {
                            status
                        }
                        continuation.resumeWithException(failure.asRuntimeException(trailers))
                    }
                }
            },
        )
        continuation.invokeOnCancellation { call.cancel("Native gRPC request canceled") }
    }

    fun isAvailable(): Boolean = pipe.isAvailable() && !channel.isShutdown

    fun afterExit(action: Runnable): Unit = pipe.afterExit(action)

    override fun close() {
        channel.shutdownNow()
        pipe.close()
    }
}
