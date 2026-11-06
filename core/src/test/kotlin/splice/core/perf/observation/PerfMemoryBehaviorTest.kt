package splice.core.perf.observation

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class PerfMemoryBehaviorTest {
    @Test
    fun `collectors share one byte allowance and release it on close`() {
        val schema = PerfMetricRegistry.schema()
        val state = requireNotNull(schema["state_budget_bytes"]).jsonPrimitive.long
        val generation = requireNotNull(schema["snapshot_generation_budget_bytes"]).jsonPrimitive.long
        val headroom = requireNotNull(schema["memory_headroom_bytes"]).jsonPrimitive.long
        assertEquals(CORE_BYTES, requireNotNull(schema["memory_budget_bytes"]).jsonPrimitive.long)
        assertEquals(CORE_BYTES, state + generation + headroom)
        val fixtures = mutableListOf<ObservationFixture>()
        try {
            var exhausted = false
            while (!exhausted && fixtures.size < COLLECTORS) {
                val fixture = ObservationFixture()
                fixtures += fixture
                fill(fixture)
                val snapshot = fixture.accumulator.snapshot()
                val maxCells = requireNotNull(schema["max_aggregate_cells"]).jsonPrimitive.long
                assertTrue(snapshot.points.size <= maxCells)
                exhausted = snapshot.coverage.issues.any { it.code == "memory_budget_exhausted" }
            }
            assertTrue(exhausted)
        } finally {
            fixtures.forEach(ObservationFixture::close)
        }
        ObservationFixture().use { fixture ->
            assertTrue(fixture.recorder.emit(fixture.scope, PerfEventKind.RUNTIME, mapOf(HEAP_BYTES to 1.0)))
            assertEquals(1.0, fixture.total(HEAP_BYTES))
        }
    }

    private fun fill(fixture: ObservationFixture) {
        val values = mapOf(
            HEAP_BYTES to 1.0,
            "runtime_heap_committed_bytes" to 1.0,
            "runtime_heap_max_bytes" to 1.0,
            "runtime_rss_bytes" to 1.0,
        )
        repeat(SCOPES) { index ->
            fixture.recorder.emit(fixture.scope.copy(model = "model-$index"), PerfEventKind.RUNTIME, values)
        }
    }
}

private const val CORE_BYTES = 16L * 1024 * 1024
private const val COLLECTORS = 16
private const val SCOPES = 128
private const val HEAP_BYTES = "runtime_heap_used_bytes"
