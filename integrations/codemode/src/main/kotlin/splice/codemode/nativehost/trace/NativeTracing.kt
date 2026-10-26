package splice.codemode.nativehost.trace

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeInvocationAction
import kotlin.coroutines.CoroutineContext

/** Trace ownership follows operations and callbacks; a session never stores an ambient caller context. */
internal class NativeTracing(private val tracer: Tracer = GlobalOpenTelemetry.getTracer("splice.codemode")) {
    val invocations = NativeTraceInvocations()

    fun start(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        remoteParent: String? = null,
        current: Context = Context.current(),
    ): NativeTraceSpan {
        val parent = remoteParent?.let(NativeTraceParents::context) ?: current
        // A no-op Java builder can return its parent. Rust's disabled tracing Span has no context to propagate.
        if (!tracer.isEnabled) return NativeTraceSpan(Span.getInvalid(), current)
        val builder = tracer.spanBuilder(name).setParent(parent)
        attributes.forEach { (key, value) -> builder.setAttribute(key, value) }
        return NativeTraceSpan(builder.startSpan(), parent)
    }
}

internal object NativeTraceContext {
    fun capture(): CoroutineContext = NativeTraceElement(Context.current())

    suspend fun <T> preserve(action: CodeModeInvocationAction<T>): T = withContext(capture()) { action.run() }
}
