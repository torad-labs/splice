package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Metadata
import io.grpc.Status
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One admission attempt owns its lease before any executable session is published. */
internal class NativeGrpcLease(
    private val localId: String,
    private val channel: NativeGrpcChannel,
    private val onEvent: NativeGrpcSessionEvent,
) : AutoCloseable {
    private val expectedClose = AtomicBoolean()
    private val call = AtomicReference<NativeGrpcCall?>()
    private val session = AtomicReference<NativeGrpcSession?>()
    private val route = AtomicReference<String?>()
    private val failure = AtomicReference<Throwable?>()
    private val failureHandler = AtomicReference<NativeGrpcSessionFailure?>()
    val first = CompletableDeferred<NativeGrpcSession>()

    fun start(request: DynamicMessage, traceparent: String? = null) {
        val opened = channel.stream(
            NativeGrpcRequest("OpenSession", request, timeoutMillis = null, traceparent = traceparent),
            object : NativeGrpcListener {
                override fun onHeaders(headers: Metadata) {
                    val token = headers.get(Metadata.Key.of(NATIVE_GRPC_ROUTE_HEADER, Metadata.ASCII_STRING_MARSHALLER))
                    if (token != null && token.toByteArray(
                            StandardCharsets.UTF_8,
                        ).size > NATIVE_GRPC_MAX_IDENTIFIER_BYTES
                    ) {
                        fail(
                            Status.INVALID_ARGUMENT.withDescription(
                                "session routing token is too long",
                            ).asRuntimeException(),
                        )
                    } else {
                        route.set(token)
                    }
                }
                override fun onMessage(message: DynamicMessage) {
                    try {
                        accept(message)
                    } catch (error: IOException) {
                        fail(error)
                    } catch (error: IllegalArgumentException) {
                        fail(error)
                    } catch (error: IllegalStateException) {
                        fail(error)
                    }
                }
                override fun onClose(status: Status, trailers: Metadata) {
                    if (!expectedClose.get()) {
                        val ended = if (status.isOk) {
                            Status.UNAVAILABLE.withDescription(
                                "session lease ended before opening",
                            )
                        } else {
                            status
                        }
                        fail(ended.asRuntimeException(trailers))
                    }
                }
            },
        )
        call.set(opened)
        if (expectedClose.get() || failure.get() != null) opened.cancel("Native code-mode lease is closed")
    }

    private fun accept(message: DynamicMessage) {
        if (expectedClose.get() || failure.get() != null) return
        val opened = NativeGrpcWire.value(message, "opened") as? DynamicMessage
        val current = session.get()
        if (current == null) {
            if (opened == null) {
                throw NativeGrpcProtocolException(
                    "gRPC code-mode session lease omitted its opening event",
                )
            }
            val id = NativeGrpcFields.identifier(opened, "session_id")
            NativeGrpcSession(localId, id, route.get()).also {
                session.set(it)
                first.complete(it)
            }
        } else {
            check(opened == null) { "gRPC code-mode host repeated the session opening event" }
            onEvent(current, message)
        }
    }

    fun activate(handler: NativeGrpcSessionFailure) {
        failureHandler.set(handler)
        failure.get()?.let { handler(it) }
    }

    private fun fail(error: Throwable) {
        if (!expectedClose.get() && failure.compareAndSet(null, error)) {
            first.completeExceptionally(error)
            failureHandler.get()?.invoke(error)
            call.get()?.cancel("Native code-mode lease failed")
        }
    }

    override fun close() {
        if (expectedClose.compareAndSet(false, true)) {
            first.completeExceptionally(IOException("gRPC code-mode session is closed"))
            call.get()?.cancel("Native code-mode session closed")
        }
    }
}
