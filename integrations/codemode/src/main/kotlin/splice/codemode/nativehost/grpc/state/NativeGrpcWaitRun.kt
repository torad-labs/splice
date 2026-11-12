package splice.codemode.nativehost.grpc.state

import com.google.protobuf.DynamicMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.serialization.json.JsonObject
import splice.codemode.nativehost.grpc.NativeGrpcSession

internal class NativeGrpcWaitRun(
    val session: NativeGrpcSession,
    val operation: Long,
    val wait: String,
    val request: DynamicMessage,
) {
    @Volatile private var job: Job? = null

    @Volatile var claim: NativeGrpcWaitClaim? = null

    @Volatile var retiring: Boolean = false

    @Volatile var waiting: Boolean = false
    var finished: Boolean = false
    var response: JsonObject? = null
    var yieldRequested: Boolean = false

    @Synchronized
    fun start(job: Job) {
        this.job = job
        if (finished) job.cancel() else job.start()
    }

    suspend fun cancelAndJoin() { job?.cancelAndJoin() }
    fun cancel() { job?.cancel() }
}
