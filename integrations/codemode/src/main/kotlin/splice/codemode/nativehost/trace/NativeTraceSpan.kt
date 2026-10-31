package splice.codemode.nativehost.trace

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeInvocationAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** An owned span survives asynchronous delivery and ends once its real operation settles. */
internal class NativeTraceSpan(private val span: Span, parent: Context) : AutoCloseable {
    private val context = parent.with(span)
    private val closed = AtomicBoolean()
    val isActive: Boolean get() = !closed.get()
    val coroutineContext: CoroutineContext = NativeTraceElement(context)
    val invocationContext = NativeInvocationContext(this)
    val traceparent: String?
        get() = span.spanContext.takeIf { it.isValid }?.let {
            "00-${it.traceId}-${it.spanId}-${if (it.isSampled) "01" else "00"}"
        }

    suspend fun <T> run(action: CodeModeInvocationAction<T>): T =
        if (isActive) withContext(coroutineContext) { action.run() } else action.run()

    fun <T> handoff(action: NativeTraceHandoff<T>): T {
        var transferred = false
        try {
            return action.run().also { transferred = true }
        } finally {
            if (!transferred) close()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) span.end()
    }
}

internal fun interface NativeTraceHandoff<T> {
    fun run(): T
}
