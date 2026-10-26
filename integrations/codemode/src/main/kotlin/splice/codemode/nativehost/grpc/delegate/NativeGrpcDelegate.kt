package splice.codemode.nativehost.grpc.delegate

import kotlinx.coroutines.Job
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.trace.NativeTraceSpan
import java.util.concurrent.atomic.AtomicBoolean

/** One host callback the client answers, a tool invocation or a notification, and the job that completes it. A
 *  cancellation can arrive before the job exists, and then the job is cancelled as it starts. */
internal class NativeGrpcDelegate(
    val session: NativeGrpcSession,
    val cell: String,
    val id: String,
    val notification: Boolean,
    val span: NativeTraceSpan? = null,
) {
    private val cancelled = AtomicBoolean()
    private var job: Job? = null

    @Synchronized
    fun cancel() {
        cancelled.set(true)
        job?.cancel()
        if (job == null) span?.close()
    }

    @Synchronized
    fun start(job: Job) {
        this.job = job
        if (cancelled.get()) job.cancel() else job.start()
    }

    fun isCancelled(): Boolean = cancelled.get()
}
