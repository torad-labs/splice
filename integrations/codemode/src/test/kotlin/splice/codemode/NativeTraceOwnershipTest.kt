package splice.codemode

import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.codemode.nativehost.grpc.NativeGrpcSession
import splice.codemode.nativehost.grpc.delegate.NativeGrpcDelegate
import splice.codemode.nativehost.trace.NativePerfContext
import splice.codemode.nativehost.trace.NativeTracing
import splice.codemode.nativehost.trace.NativeTracingProvider
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.ElapsedClock
import splice.core.util.WallClock

class NativeTraceOwnershipTest {
    @Test
    fun `shutdown seals admission but owned spans export before their measured terminals`() {
        var now = 10L
        val exporter = OwnershipExporter { now = 500L }
        val events = mutableListOf<PerfObservation>()
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "config"),
            PerfObserver { event ->
                if (event.kind == PerfEventKind.TOOL_FINISHED && event.values["tool_native_span"] == 1.0) {
                    assertTrue(
                        exporter.spans.any { it.spanId == event.links.spanId },
                        "SDK end precedes terminal publication",
                    )
                }
                events.add(event)
            },
            ElapsedClock { now },
            WallClock { now },
        )
        val request = recorder.request(PerfScope(head = "codex"), now, now)
        NativeTracingProvider(exporter).use { provider ->
            val tracing = NativeTracing(provider.tracer, provider)
            val parent = NativePerfContext.attach(Context.root(), request)
            tracing.start("code_mode.grpc.execute", current = parent).use { execute ->
                tracing.start("code_mode.grpc.callback", current = execute.context).use { callback ->
                    now = 30L
                    request.finish("ok")
                    provider.close() // force shutdown before either operation's completion handler
                    callback.outcome("cancelled")
                    callback.close()
                    val terminal = events.single { it.kind == PerfEventKind.TOOL_FINISHED }
                    assertEquals(30L, terminal.stamp.monotonicMs)
                    assertEquals(30L, terminal.stamp.wallMs)
                    assertEquals(20.0, terminal.values["tool_duration_ms"])
                    assertEquals(OutcomeTag.CANCELLED.wire, terminal.outcome)
                    assertEquals(request.links.requestId, terminal.links.requestId)
                    execute.close()
                    assertEquals(2, exporter.spans.size)
                    assertEquals(2, events.count { it.kind == PerfEventKind.TOOL_FINISHED })
                    val afterShutdown = provider.tracer.spanBuilder("after-shutdown").startSpan()
                    assertFalse(afterShutdown.isRecording, "the last ticket closes the owned SDK provider")
                    afterShutdown.end()
                    assertSealed(tracing, parent)
                    assertNull(events.last().values["tool_native_span"])
                    assertEquals(2, exporter.spans.size, "late local observations do not fabricate SDK spans")
                }
            }
        }
        assertFalse(exporter.closed, "the process exporter is borrowed")
    }

    @Test
    fun `terminal observer failure still ends the span and releases its provider ticket`() {
        val exporter = OwnershipExporter()
        val recorder = recorder { event ->
            check(event.kind != PerfEventKind.TOOL_FINISHED) { "terminal observer failed" }
            true
        }
        val request = recorder.request(PerfScope(), 0, 0)
        NativeTracingProvider(exporter).use { provider ->
            val tracing = NativeTracing(provider.tracer, provider)
            val span = tracing.start(
                "code_mode.grpc.execute",
                current = NativePerfContext.attach(Context.root(), request),
            )
            provider.close()
            val failure = assertThrows(IllegalStateException::class.java) { span.close() }
            assertEquals("terminal observer failed", failure.message)
            assertEquals(1, exporter.spans.size)
            span.close()
            assertEquals(1, exporter.spans.size, "a failed publication is not retried")
            val afterShutdown = provider.tracer.spanBuilder("after-shutdown").startSpan()
            assertFalse(afterShutdown.isRecording)
            afterShutdown.end()
        }
        assertFalse(exporter.closed)
    }

    @Test
    fun `failed tool observation creation ends its SDK span and releases admission`() {
        val exporter = OwnershipExporter()
        val recorder = recorder { event ->
            check(event.kind != PerfEventKind.TOOL_STARTED) { "tool observer failed" }
            true
        }
        val request = recorder.request(PerfScope(), 0, 0)
        NativeTracingProvider(exporter).use { provider ->
            val tracing = NativeTracing(provider.tracer, provider)
            val failure = assertThrows(IllegalStateException::class.java) {
                tracing.start("code_mode.grpc.execute", current = NativePerfContext.attach(Context.root(), request))
            }
            assertEquals("tool observer failed", failure.message)
            assertEquals(1, exporter.spans.size)
            provider.close()
            val afterShutdown = provider.tracer.spanBuilder("after-shutdown").startSpan()
            assertFalse(afterShutdown.isRecording)
            afterShutdown.end()
        }
        assertFalse(exporter.closed)
    }

    @Test
    fun `a callback registered after the close sweep releases its already admitted span`() {
        val exporter = OwnershipExporter()
        val events = mutableListOf<PerfObservation>()
        val recorder = recorder { events.add(it) }
        val request = recorder.request(PerfScope(), 0, 0)
        NativeTracingProvider(exporter).use { provider ->
            val tracing = NativeTracing(provider.tracer, provider)
            val span = tracing.start(
                "code_mode.grpc.callback",
                current = NativePerfContext.attach(Context.root(), request),
            )
            tracing.invocations.close()
            provider.close()
            tracing.invocations.add("late", span)
            assertNull(tracing.invocations.get("late"))
            assertEquals(1, exporter.spans.size)
            val terminal = events.single { it.kind == PerfEventKind.TOOL_FINISHED }
            assertEquals(OutcomeTag.CANCELLED.wire, terminal.outcome)
            assertEquals(exporter.spans.single().spanId, terminal.links.spanId)
            val afterShutdown = provider.tracer.spanBuilder("after-shutdown").startSpan()
            assertFalse(afterShutdown.isRecording)
            afterShutdown.end()
        }
        assertFalse(exporter.closed)
    }

    @Test
    fun `a cancel after a callback's outcome is decided leaves the outcome, and one before it records cancelled`() {
        val events = mutableListOf<PerfObservation>()
        val request = recorder { events.add(it) }.request(PerfScope(), 0, 0)
        val tracing = NativeTracing()
        val parent = NativePerfContext.attach(Context.root(), request)
        val session = NativeGrpcSession("local", "host", null)
        fun delegate(id: String) = NativeGrpcDelegate(
            session,
            "cell",
            id,
            false,
            tracing.start("code_mode.grpc.callback", current = parent),
        )

        val answered = delegate("answered")
        answered.span?.outcome("bad") // the client answered the callback with an error
        answered.cancel() // a closing session sweeps it before its completion job has ended
        delegate("unanswered").cancel()

        val outcomes = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }.map { it.outcome }
        assertEquals(listOf(OutcomeTag.UNEXPECTED.wire, OutcomeTag.CANCELLED.wire), outcomes)
    }

    private fun assertSealed(tracing: NativeTracing, parent: Context) {
        tracing.start("code_mode.grpc.execute", current = parent).use { late ->
            assertNull(late.traceparent, "sealed admission cannot return a native parent identity")
        }
    }

    private fun recorder(observer: PerfObserver) = PerfRecorder(
        PerfIdentity("boot", "build", "version", "config"),
        observer,
        ElapsedClock { 0 },
        WallClock { 0 },
    )
}

private class OwnershipExporter(private val afterExport: () -> Unit = {}) : SpanExporter {
    val spans = mutableListOf<SpanData>()
    var closed = false

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        afterExport()
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        closed = true
        return CompletableResultCode.ofSuccess()
    }
}
