package splice.codemode.nativehost.trace

import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModeInvocationContext
import splice.upstream.codemode.CodeModeInvocationContextKey
import kotlin.coroutines.AbstractCoroutineContextElement

/** Retains this callback's context across buffered provider delivery without retaining the caller's job. */
internal class NativeInvocationContext(private val span: NativeTraceSpan) :
    AbstractCoroutineContextElement(CodeModeInvocationContextKey), CodeModeInvocationContext {
    override val isActive: Boolean get() = span.isActive
    private val bufferedBytes = java.util.concurrent.atomic.AtomicLong()

    override fun buffered(bytes: Int) {
        span.phase(mapOf("tool_buffered_bytes" to bufferedBytes.addAndGet(bytes.toLong()).toDouble()))
    }

    override fun delivered(bytes: Int, ageMs: Long) {
        val remaining = bufferedBytes.addAndGet(-bytes.toLong()).coerceAtLeast(0)
        span.phase(
            mapOf(
                "tool_buffered_bytes" to remaining.toDouble(),
                "tool_buffered_ms" to ageMs.coerceAtLeast(0).toDouble(),
            ),
        )
    }

    override fun discarded(bytes: Int) {
        span.phase(mapOf("tool_buffered_bytes" to bufferedBytes.addAndGet(-bytes.toLong()).coerceAtLeast(0).toDouble()))
    }

    override suspend fun <T> within(action: CodeModeInvocationAction<T>): T {
        if (!isActive) return action.run()
        return span.run { withContext(this@NativeInvocationContext) { action.run() } }
    }
}
