package splice.codemode.nativehost.trace

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.withContext
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfToolRecorder
import splice.core.util.MonoClock
import splice.upstream.codemode.CodeModeInvocationAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** An owned span survives asynchronous delivery and ends once its real operation settles. */
internal class NativeTraceSpan(
    private val span: Span,
    parent: Context,
    val observations: PerfToolRecorder? = null,
    private val operation: String = "unknown",
) : AutoCloseable {
    val context: Context = NativePerfContext.operation(parent.with(span), observations)
    private val startedAt = MonoClock.nowMs()

    @Volatile private var outcome = "?"

    @Volatile private var repliedAt: Long? = null
    private val closed = AtomicBoolean()
    val pendingSinceMs: Long? get() = startedAt.takeIf {
        operation == "code_mode.grpc.callback" && repliedAt == null && !closed.get()
    }
    val isActive: Boolean get() = !closed.get()
    val coroutineContext: CoroutineContext = NativeTraceElement(context)
    val invocationContext = NativeInvocationContext(this)
    val traceparent: String?
        get() = span.spanContext.takeIf { it.isValid }?.let {
            "00-${it.traceId}-${it.spanId}-${if (it.isSampled) "01" else "00"}"
        }

    suspend fun <T> run(action: CodeModeInvocationAction<T>): T =
        if (isActive) withContext(coroutineContext) { action.run() } else action.run()

    fun outcome(value: String) { outcome = value }

    fun phase(values: Map<String, Double>) { observations?.observe(PerfEventKind.TOOL_PHASE, values) }

    @Synchronized
    fun replyReceived() {
        val now = MonoClock.nowMs()
        if (repliedAt == null) {
            repliedAt = now
            phase(mapOf("tool_callback_wait_ms" to (now - startedAt).coerceAtLeast(0).toDouble()))
        }
    }

    fun <T> handoff(action: NativeTraceHandoff<T>): T {
        var transferred = false
        try {
            return action.run().also { transferred = true }
        } finally {
            if (!transferred) close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            val reply = repliedAt
            val metric = when {
                reply != null -> "tool_delivery_ms"
                operation == "code_mode.grpc.callback" -> "tool_pending_reply_ms"
                else -> "tool_execution_ms"
            }
            observations?.finish(
                outcome,
                mapOf(metric to (MonoClock.nowMs() - (reply ?: startedAt)).coerceAtLeast(0).toDouble()),
            )
            span.end()
        }
    }
}

internal fun interface NativeTraceHandoff<T> {
    fun run(): T
}
