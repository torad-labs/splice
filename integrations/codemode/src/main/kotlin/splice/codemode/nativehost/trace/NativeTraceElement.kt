package splice.codemode.nativehost.trace

import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Restore the previous thread context on every suspension, including Dispatchers.IO boundaries. */
internal class NativeTraceElement(private val context: Context) :
    ThreadContextElement<Scope>, AbstractCoroutineContextElement(NativeTraceElementKey) {
    override fun updateThreadContext(context: CoroutineContext): Scope = this.context.makeCurrent()

    override fun restoreThreadContext(context: CoroutineContext, oldState: Scope): Unit = oldState.close()
}

internal object NativeTraceElementKey : CoroutineContext.Key<NativeTraceElement>
