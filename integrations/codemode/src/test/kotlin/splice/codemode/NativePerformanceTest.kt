package splice.codemode

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import splice.core.perf.observation.PerfEventKind
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObservation
import splice.core.perf.observation.PerfObserver
import splice.core.perf.observation.PerfRecorder
import splice.core.perf.observation.PerfScope
import splice.core.util.ElapsedClock
import splice.core.util.MonoClock
import splice.core.util.WallClock
import splice.upstream.codemode.CodeModePerfContext
import splice.upstream.codemode.CodeModeResult
import splice.upstream.codemode.CodeModeStep
import java.util.concurrent.CopyOnWriteArrayList

class NativePerformanceTest {
    @ParameterizedTest
    @ValueSource(strings = ["throw new Error('observed failure');", "const = ;"])
    fun `script failures are measured as failed tools after a successful RPC`(source: String) = nativeProcessTest {
        val events = CopyOnWriteArrayList<PerfObservation>()
        val published = CompletableDeferred<Unit>()
        val exporter = NativePerformanceExporter()
        val request = recorder(events, published, 1)
            .request(PerfScope(head = "codex"), MonoClock.nowMs(), System.currentTimeMillis())
        NativeCodeModeRuntime(traceExporter = exporter).use { runtime ->
            val cell = withContext(CodeModePerfContext(request)) {
                runtime.startSession("failed-script", source, emptySet())
            }
            assertNotNull((cell.advance() as CodeModeStep.Completed).error)
            request.finish("good")
        }
        published.await()
        val finished = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(listOf("bad"), finished.map { it.scope.outcome })
        assertPublishedSpans(finished, exporter)
    }

    @Test
    fun `a caught callback failure stays failed while its script succeeds`() = nativeProcessTest {
        val events = CopyOnWriteArrayList<PerfObservation>()
        val published = CompletableDeferred<Unit>()
        val exporter = NativePerformanceExporter()
        val request = recorder(events, published, 2)
            .request(PerfScope(head = "codex"), MonoClock.nowMs(), System.currentTimeMillis())
        NativeCodeModeRuntime(traceExporter = exporter).use { runtime ->
            val cell = withContext(CodeModePerfContext(request)) {
                runtime.startSession(
                    "caught-callback",
                    "try { await tools.Read({}); } catch (_) { text('recovered'); }",
                    setOf("Read"),
                )
            }
            val call = (cell.advance() as CodeModeStep.Calls).calls.single()
            val completed = cell.advance(listOf(CodeModeResult(call.id, "denied", isError = true)))
                as CodeModeStep.Completed
            assertNull(completed.error)
            assertEquals("recovered", completed.output)
            request.finish("good")
        }
        published.await()
        val finished = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }
        assertEquals(listOf("bad", "good"), finished.mapNotNull { it.scope.outcome }.sorted())
        val outcomes = finished.associate { event ->
            exporter.spans.single { it.spanId == event.links.spanId }.name to event.scope.outcome
        }
        assertEquals(mapOf("code_mode.grpc.execute" to "good", "code_mode.grpc.callback" to "bad"), outcomes)
    }

    @Test
    fun `native callbacks keep request links after client close and borrow the exporter`() = nativeProcessTest {
        val events = CopyOnWriteArrayList<PerfObservation>()
        val terminalsPublished = CompletableDeferred<Unit>()
        val exporter = NativePerformanceExporter()
        val recorder = PerfRecorder(
            PerfIdentity("boot", "build", "version", "config"),
            observer(events, terminalsPublished),
            ElapsedClock(MonoClock::nowMs),
            WallClock(System::currentTimeMillis),
        )
        val request = recorder.request(PerfScope(head = "codex"), MonoClock.nowMs(), System.currentTimeMillis())
        request.update(
            request.scope,
            request.links.copy(traceId = "a".repeat(32), spanId = "b".repeat(16), traceFlags = "00"),
        )
        val attempt = request.attempt("sse")
        NativeCodeModeRuntime(traceExporter = exporter).use { runtime ->
            val cell = withContext(CodeModePerfContext(request, attempt.links)) {
                runtime.startSession("observed", "text(await tools.Read({secret:'private-value'}));", setOf("Read"))
            }
            val call = (cell.advance() as CodeModeStep.Calls).calls.single()
            request.finish("good")
            val result = cell.advance(listOf(CodeModeResult(call.id, "accepted"))) as CodeModeStep.Completed
            assertEquals("accepted", result.output)
            val warm = withContext(CodeModePerfContext(request, attempt.links)) {
                runtime.startSession("observed", "text('warm');", emptySet())
            }
            assertEquals("warm", (warm.advance() as CodeModeStep.Completed).output)
        }
        // Runtime shutdown cancels asynchronous finalizers. Await their actual publications under
        // nativeProcessTest's existing 30s deadline; no sleep or second probe can manufacture them.
        terminalsPublished.await()
        val finished = events.filter { it.kind == PerfEventKind.TOOL_FINISHED }
        assertTrue(finished.size >= 3)
        assertTrue(
            finished.all {
                it.links.requestId == request.links.requestId && it.links.attemptId == attempt.links.attemptId
            },
        )
        assertEquals(finished.size, finished.map { it.links.toolId }.toSet().size)
        assertTrue(finished.all { it.values["tool_native_span"] == 1.0 })
        assertPublishedSpans(finished, exporter)
        assertTrue(events.any { it.values.containsKey("tool_callback_wait_ms") })
        val coldStarts = events.mapNotNull { it.values["tool_cold_start_ms"] }
        assertEquals(2, coldStarts.size)
        assertTrue(coldStarts.first() > 0.0)
        assertTrue(events.any { it.values["tool_cold_start_ms"] == 0.0 })
        assertFalse(events.toString().contains("private-value"))
        assertFalse(exporter.closed)
    }

    private fun observer(
        events: CopyOnWriteArrayList<PerfObservation>,
        published: CompletableDeferred<Unit>,
        expected: Int = 3,
    ): PerfObserver = PerfObserver { event ->
        events.add(event).also {
            val finished = event.kind == PerfEventKind.TOOL_FINISHED
            if (finished && events.count { it.kind == PerfEventKind.TOOL_FINISHED } >= expected) {
                published.complete(Unit)
            }
        }
    }

    private fun recorder(
        events: CopyOnWriteArrayList<PerfObservation>,
        published: CompletableDeferred<Unit>,
        expected: Int,
    ) = PerfRecorder(
        PerfIdentity("boot", "build", "version", "config"),
        observer(events, published, expected),
        ElapsedClock(MonoClock::nowMs),
        WallClock(System::currentTimeMillis),
    )

    private fun assertPublishedSpans(finished: List<PerfObservation>, exporter: NativePerformanceExporter) {
        assertTrue(finished.all { event -> exporter.spans.any { it.spanId == event.links.spanId } }) {
            "finished=${finished.size}, exported=${exporter.spans.size}, " +
                "missing=${finished.count { event -> exporter.spans.none { it.spanId == event.links.spanId } }}"
        }
    }
}

private class NativePerformanceExporter : SpanExporter {
    val spans = CopyOnWriteArrayList<SpanData>()
    var closed = false
    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        this.spans.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode {
        closed = true
        return CompletableResultCode.ofSuccess()
    }
}
