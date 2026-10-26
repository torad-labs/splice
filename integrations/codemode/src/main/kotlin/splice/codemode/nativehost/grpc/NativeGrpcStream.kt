package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/** Independent response stream; dropping it retires this RPC without closing the session lease. */
internal class NativeGrpcStream(
    channel: NativeGrpcChannel,
    request: NativeGrpcRequest,
) : NativeGrpcListener, AutoCloseable {
    private val messages = Channel<DynamicMessage>(Channel.UNLIMITED)
    private val headers = CompletableDeferred<Unit>()
    private val call = channel.stream(request, this)

    override fun onHeaders(headers: Metadata) {
        this.headers.complete(Unit)
    }

    override fun onMessage(message: DynamicMessage) {
        messages.trySend(message)
    }

    override fun onClose(status: Status, trailers: Metadata) {
        val failure = if (status.isOk) null else StatusRuntimeException(status, trailers)
        if (failure == null) headers.complete(Unit) else headers.completeExceptionally(failure)
        messages.close(failure)
    }

    suspend fun opened(): Unit = headers.await()

    suspend fun next(endedDiagnostic: String): DynamicMessage {
        val received = messages.receiveCatching()
        return received.getOrNull() ?: throw (
            received.exceptionOrNull() ?: NativeGrpcProtocolException(
                endedDiagnostic,
            )
            )
    }

    override fun close() {
        call.cancel("CodeMode observation retired")
        headers.cancel()
        messages.cancel()
    }
}
