package splice.codemode.nativehost.grpc.state

import kotlinx.coroutines.CompletableDeferred
import splice.codemode.nativehost.grpc.NativeGrpcSession

internal data class NativeGrpcExecutionRecord(
    val session: NativeGrpcSession,
    val operationId: Long,
    val executionId: String,
    val toolCallId: String,
    val enabledTools: Map<Pair<String, String>, Int>,
) {
    var cell: String? = null
    var started: Boolean = false
    var ready: Boolean = false
    var closed: Boolean = false
    val notificationClosure = CompletableDeferred<Unit>()
}

internal data class NativeGrpcPendingCallback(val execution: Pair<String, String>, val cell: String)

internal class NativeGrpcCallbackState {
    val seenInvocations = NativeGrpcRecentIds()
    val cancelledInvocations = NativeGrpcRecentIds()
    val invocations = HashMap<String, NativeGrpcPendingCallback>()
    val notifications = HashMap<String, NativeGrpcPendingCallback>()
}
