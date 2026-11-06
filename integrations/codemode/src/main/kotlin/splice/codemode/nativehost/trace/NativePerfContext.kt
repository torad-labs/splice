package splice.codemode.nativehost.trace

import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import splice.core.perf.observation.PerfLinks
import splice.core.perf.observation.PerfRequestRecorder
import splice.core.perf.observation.PerfToolRecorder

/** Extends the existing native trace context, including when the SDK is disabled. */
internal object NativePerfContext {
    private val requestKey = ContextKey.named<PerfRequestRecorder>("splice.perf.request")
    private val attemptKey = ContextKey.named<PerfLinks>("splice.perf.attempt")
    private val toolKey = ContextKey.named<PerfToolRecorder>("splice.perf.tool")

    fun operation(context: Context, tool: PerfToolRecorder?): Context =
        if (tool == null) context else context.with(toolKey, tool)

    fun timedOut() {
        Context.current().get(toolKey)?.observe(
            splice.core.perf.observation.PerfEventKind.TOOL_PHASE,
            mapOf("tool_timeouts" to 1.0),
        )
    }

    fun attach(current: Context, request: PerfRequestRecorder?, attempt: PerfLinks? = null): Context {
        if (request == null) return current
        val links = attempt?.takeIf { it.requestId == request.links.requestId } ?: request.links
        val parent = if (Span.fromContext(current).spanContext.isValid) {
            current
        } else {
            NativeTraceParents.context("00-${links.traceId}-${links.spanId}-${links.traceFlags ?: "00"}") ?: current
        }
        return parent.with(
            requestKey,
            request,
        ).with(attemptKey, links).with(NativeStartTiming.key, NativeStartMeasurements())
    }

    fun tool(name: String, span: Span, parent: Context): PerfToolRecorder? {
        if (name !in setOf("code_mode.grpc.execute", "code_mode.grpc.callback")) return null
        val context = span.spanContext
        val links = if (span.isRecording && context.spanId != Span.fromContext(
                parent,
            ).spanContext.spanId
        ) {
            PerfLinks(
                attemptId = parent.get(attemptKey)?.attemptId,
                traceId = context.traceId,
                spanId = context.spanId,
                parentSpanId = Span.fromContext(parent).spanContext.takeIf { it.isValid }?.spanId,
                traceFlags = if (context.isSampled) "01" else "00",
            )
        } else {
            PerfLinks(attemptId = parent.get(attemptKey)?.attemptId, parentSpanId = parent.get(attemptKey)?.spanId)
        }
        return parent.get(requestKey)?.tool(links)?.also { tool ->
            if (name == "code_mode.grpc.execute") {
                val values = parent.get(NativeStartTiming.key)?.snapshot().orEmpty() + ("tool_calls_per_turn" to 1.0)
                tool.observe(splice.core.perf.observation.PerfEventKind.TOOL_PHASE, values)
            }
        }
    }
}
