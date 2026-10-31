package splice.codemode.nativehost.grpc

import com.google.protobuf.DynamicMessage
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.withTimeoutOrNull

/** Source transport deadlines bound native RPCs, independently of upstream model silence. */
internal object NativeGrpcDeadlines {
    suspend fun <T> startup(operation: String, observation: NativeGrpcObservation<T>): T =
        within(NATIVE_GRPC_TRANSPORT_MS, operation, observation)

    suspend fun <T> observation(
        request: DynamicMessage,
        operation: String = "wait",
        observation: NativeGrpcObservation<T>,
    ): T {
        val supplied = NativeGrpcWire.value(request, "yield_time_ms") as? Long
        val runtime = supplied?.toULong() ?: DEFAULT_EXEC_YIELD_MS
        val maximum = Long.MAX_VALUE.toULong() - NATIVE_GRPC_OBSERVATION_OVERHEAD_MS
        // u64 values are preserved on the wire; budgets beyond the JVM clock range have no finite deadline here.
        return if (runtime > maximum) {
            observation.await()
        } else {
            within(
                (runtime + NATIVE_GRPC_OBSERVATION_OVERHEAD_MS).toLong(),
                operation,
                observation,
            )
        }
    }

    private suspend fun <T> within(millis: Long, operation: String, observation: NativeGrpcObservation<T>): T {
        val result = withTimeoutOrNull(millis) { NativeGrpcTimed(observation.await()) }
            ?: throw NativeGrpcProtocolException(
                "gRPC code-mode host timed out waiting for $operation response",
                invalidatesSession = true,
            )
        return result.value
    }

    fun invalidatesSession(error: StatusRuntimeException, routed: Boolean): Boolean =
        error.status.code == Status.Code.NOT_FOUND || invalidatesRequest(error, routed)

    fun invalidatesRequest(error: StatusRuntimeException, routed: Boolean): Boolean =
        routed && error.status.code == Status.Code.UNAVAILABLE && error.trailers?.get(
            Metadata.Key.of("x-code-mode-route-lost", Metadata.ASCII_STRING_MARSHALLER),
        ) == "true"
}

// deadline.rs adds 60 seconds to the operation's runtime budget; operations.rs adds one second to yields.
private const val NATIVE_GRPC_OBSERVATION_OVERHEAD_MS = 61_000uL

// why: Codex rust-v0.164.0 code-mode/src/grpc_session/deadline.rs:7 sets TRANSPORT_TIMEOUT to 60 seconds, which
// startup (deadline.rs:14) adds to a zero runtime budget (deadline.rs:93).
private const val NATIVE_GRPC_TRANSPORT_MS = 60_000L

// Protocol default, distinct from the current core handler's configured 30-second foreground default.
private const val DEFAULT_EXEC_YIELD_MS = 10_000uL

private data class NativeGrpcTimed<T>(val value: T)

internal fun interface NativeGrpcObservation<T> {
    suspend fun await(): T
}
