package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.Status

internal const val NATIVE_GRPC_ROUTE_HEADER: String = "x-code-mode-route"
internal const val NATIVE_GRPC_BUFFERED_HEADER: String = "codex-code-mode-result-buffered-for-ns"
internal const val NATIVE_GRPC_MAX_IDENTIFIER_BYTES: Int = 256

internal data class NativeGrpcRequest(
    val method: String,
    val message: DynamicMessage,
    val route: String? = null,
    val timeoutMillis: Long? = 60_000,
    val traceparent: String? = null,
)

internal data class NativeGrpcReply(
    val message: DynamicMessage,
    val headers: Metadata,
    val trailers: Metadata,
)

internal interface NativeGrpcListener {
    fun onHeaders(headers: Metadata): Unit = Unit
    fun onMessage(message: DynamicMessage)
    fun onClose(status: Status, trailers: Metadata)
}

internal class NativeGrpcCall(private val call: ClientCall<DynamicMessage, DynamicMessage>) {
    fun cancel(reason: String) {
        call.cancel(reason, null)
    }
}
