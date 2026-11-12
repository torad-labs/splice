package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.observation.aggregate.PerfMemoryAccount

internal class PerfTerminalPressureTest {
    @Test
    fun `first terminal observations close live work even when collection memory is full`() {
        closesAtCapacity(warm = false)
    }

    @Test
    fun `full collection memory preserves completion totals and terminal deduplication`() {
        closesAtCapacity(warm = true)
    }

    private fun closesAtCapacity(warm: Boolean) {
        val before = PerfObservationBudget.status().reservedBytes
        ObservationFixture().use { fixture ->
            if (warm) finish(start(fixture))
            val work = start(fixture)
            PerfMemoryAccount().use { pressure ->
                val available = PerfObservationBudget.status().let { it.allocatableLimitBytes - it.reservedBytes }
                assertTrue(pressure.reserve(available))
                finish(work)
                assertClosed(fixture, warm)
            }
            val completed = families.associateWith { fixture.total("${it}_finished") }
            repeatTerminals(fixture, work)
            assertFalse(fixture.recorder.emit(fixture.scope, PerfEventKind.REQUEST_ARRIVED, links = work.request.links))
            assertIdle(fixture)
            completed.forEach { (family, total) -> assertEquals(total, fixture.total("${family}_finished")) }
        }
        assertEquals(before, PerfObservationBudget.status().reservedBytes)
    }

    private fun assertClosed(fixture: ObservationFixture, warm: Boolean) {
        assertIdle(fixture)
        val budget = PerfObservationBudget.status()
        assertTrue(budget.reservedBytes <= budget.allocatableLimitBytes)
        if (warm) families.forEach { assertEquals(2.0, fixture.total("${it}_finished")) }
    }

    private fun repeatTerminals(fixture: ObservationFixture, work: ActiveWork) {
        terminals(work).forEach { (kind, links) ->
            repeat(2) {
                assertFalse(fixture.recorder.emit(fixture.scope, kind, links = links, outcome = OutcomeTag.OK.wire))
            }
        }
    }

    @Test
    fun `an unknown terminal does not open work when its delayed arrival follows`() {
        ObservationFixture().use { fixture ->
            val links = PerfLinks(requestId = "late-arrival")
            fixture.recorder.emit(
                fixture.scope,
                PerfEventKind.REQUEST_FINISHED,
                links = links,
                outcome = OutcomeTag.OK.wire,
            )
            assertFalse(fixture.recorder.emit(fixture.scope, PerfEventKind.REQUEST_ARRIVED, links = links))
            assertFalse(fixture.recorder.emit(fixture.scope, PerfEventKind.REQUEST_FINISHED, links = links))
            assertEquals(1.0, fixture.total("requests_finished"))
            assertEquals(0.0, fixture.total("requests_arrived"))
            assertTrue(fixture.accumulator.snapshot().coverage.issues.any { it.code == "terminal_without_start" })
        }
    }

    private fun start(fixture: ObservationFixture): ActiveWork {
        val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
        return ActiveWork(request, request.attempt("sse"), request.tool())
    }

    private fun finish(work: ActiveWork) {
        // Attempts and tools may outlive the client request.
        work.request.finish(OutcomeTag.OK.wire)
        work.attempt.finish(OutcomeTag.OK.wire)
        work.tool.finish(OutcomeTag.OK.wire)
    }

    private fun terminals(work: ActiveWork): List<Pair<PerfEventKind, PerfLinks>> = listOf(
        PerfEventKind.REQUEST_FINISHED to work.request.links,
        PerfEventKind.ATTEMPT_FINISHED to work.attempt.links,
        PerfEventKind.TOOL_FINISHED to work.tool.links,
    )

    private fun assertIdle(fixture: ObservationFixture) {
        families.forEach { family ->
            assertEquals(0.0, fixture.points("${family}_live").sumOf { it.last ?: 0.0 })
        }
    }

    private data class ActiveWork(
        val request: PerfRequestRecorder,
        val attempt: PerfAttemptRecorder,
        val tool: PerfToolRecorder,
    )

    private val families = listOf("requests", "attempts", "tools")
}
