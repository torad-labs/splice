package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Keep the independent tool subscription and lease alive for the same session generation. */
internal class NativeGrpcSessionBinding(
    val session: NativeGrpcSession,
    private val lease: NativeGrpcLease,
) : AutoCloseable {
    val closing = AtomicBoolean()
    val closed = AtomicBoolean()
    private val subscription = AtomicReference<NativeGrpcCall?>()
    private val ready = CompletableDeferred<Unit>()

    suspend fun subscribe(
        channel: NativeGrpcChannel,
        sink: NativeGrpcSessionSink,
        traceparent: String? = null,
        failed: NativeGrpcSessionFailure,
    ) {
        val request = NativeGrpcWire.message("SubscribeToToolCallsRequest", mapOf("session_id" to session.id))
        val stream = channel.stream(
            NativeGrpcRequest(
                "SubscribeToToolCalls",
                request,
                session.route,
                timeoutMillis = null,
                traceparent = traceparent,
            ),
            object : NativeGrpcListener {
                override fun onHeaders(headers: Metadata) {
                    ready.complete(Unit)
                }
                override fun onMessage(message: DynamicMessage) {
                    if (!closed.get()) {
                        try {
                            sink.call(session, message)
                        } catch (error: IOException) {
                            failed(error)
                        } catch (error: IllegalArgumentException) {
                            failed(error)
                        } catch (error: IllegalStateException) {
                            failed(error)
                        }
                    }
                }
                override fun onClose(status: Status, trailers: Metadata) {
                    if (!closing.get() && !closed.get()) {
                        val ended = if (status.isOk) {
                            Status.UNAVAILABLE.withDescription("gRPC code-mode tool subscription closed unexpectedly")
                        } else {
                            status
                        }
                        val error = ended.asRuntimeException(trailers)
                        ready.completeExceptionally(error)
                        failed(error)
                    }
                }
            },
        )
        subscription.set(stream)
        if (closed.get()) stream.cancel("Native code-mode session closed during subscription")
        ready.await()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ready.completeExceptionally(IOException("Native gRPC session closed during subscription"))
            lease.close()
            subscription.get()?.cancel("Native code-mode session closed")
        }
    }
}
