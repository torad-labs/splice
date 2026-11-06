package splice.codemode.nativehost.trace

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import splice.upstream.codemode.CodeModeInvocationAction
import splice.upstream.codemode.CodeModePerfContextKey
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
        val parent = remoteParent?.let(
            NativeTraceParents::context,
        )?.let { current.with(Span.fromContext(it)) } ?: current
        // A no-op Java builder can return its parent. Rust's disabled tracing Span has no context to propagate.
        if (!tracer.isEnabled) {
            return NativeTraceSpan(
                Span.getInvalid(),
                parent,
                NativePerfContext.tool(name, Span.getInvalid(), parent),
                name,
            )
        }
        val builder = tracer.spanBuilder(name).setParent(parent)
        attributes.forEach { (key, value) -> builder.setAttribute(key, value) }
        val span = builder.startSpan()
        return NativeTraceSpan(span, parent, NativePerfContext.tool(name, span, parent), name)
    }
}

internal object NativeTraceContext {
    fun capture(): CoroutineContext = NativeTraceElement(Context.current())

    suspend fun <T> start(action: CodeModeInvocationAction<T>): T = preserve {
        // One execution owns its preparation readings, including through nested preserved scopes.
        val context = Context.current().with(NativeStartTiming.key, NativeStartMeasurements())
        withContext(NativeTraceElement(context)) { action.run() }
    }

    suspend fun <T> preserve(action: CodeModeInvocationAction<T>): T {
        val perf = currentCoroutineContext()[CodeModePerfContextKey]
        val context = NativePerfContext.attach(Context.current(), perf?.request, perf?.attempt)
        return withContext(NativeTraceElement(context)) { action.run() }
    }
}
