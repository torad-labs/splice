package splice.codemode.nativehost.grpc.state

import splice.codemode.nativehost.grpc.NativeGrpcChannel
import splice.codemode.nativehost.grpc.NativeGrpcRequest
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.grpc.NativeGrpcWire
import splice.core.util.Cancellables

internal data class NativeGrpcAbandoned(val session: NativeGrpcSession, val cell: String?)

/** ExecutionOwnership drops local state first, then terminates a known cell without replaying its source. */
internal object NativeGrpcAbandonedCells {
    suspend fun terminate(channel: NativeGrpcChannel, abandoned: NativeGrpcAbandoned) {
        val cell = abandoned.cell ?: return
        Cancellables.runCatchingBestEffort {
            channel.unary(
                NativeGrpcRequest(
                    "Terminate",
                    NativeGrpcWire.message(
                        "TerminateRequest",
                        mapOf("session_id" to abandoned.session.id, "cell_id" to cell),
                    ),
                    abandoned.session.route,
                ),
            )
        }
    }
}
