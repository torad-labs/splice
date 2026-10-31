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

    override suspend fun <T> within(action: CodeModeInvocationAction<T>): T {
        if (!isActive) return action.run()
        return span.run { withContext(this@NativeInvocationContext) { action.run() } }
    }
}
