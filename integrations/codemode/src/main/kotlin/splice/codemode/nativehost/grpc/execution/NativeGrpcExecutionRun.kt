package splice.codemode.nativehost.grpc.execution

import com.google.protobuf.DynamicMessage
import splice.codemode.nativehost.NativeOperation
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.grpc.NativeGrpcStream
import splice.codemode.nativehost.trace.NativeTraceSpan
import java.util.concurrent.atomic.AtomicBoolean

/** One execute operation from its Execute request to its first outcome: what the host accepted, and whether the
 *  run has ended. */
internal class NativeGrpcExecutionRun(
    val session: NativeGrpcSession,
    val operation: Long,
    val execution: String,
    val request: DynamicMessage,
    val owner: NativeOperation?,
    val span: NativeTraceSpan,
) {
    @Volatile var abandoned = false

    @Volatile var activity = "execution"

    @Volatile var accepted = false

    @Volatile var cell = ""

    @Volatile var stream: NativeGrpcStream? = null
    private val finished = AtomicBoolean()

    fun isFinished(): Boolean = finished.get()

    fun finish(): Boolean = finished.compareAndSet(false, true)
}
