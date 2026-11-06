package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag
import splice.core.perf.PerfKeys

internal class PerfClientBoundaryTest {
    @Test
    fun `successful opening frame records first client byte while meaningful content remains unknown`() {
        ObservationFixture().use { fixture ->
            val request = fixture.recorder.request(fixture.scope, fixture.now, fixture.wall)
            fixture.advance(1000)
            request.observe(PerfEventKind.CLIENT_PROGRESS, mapOf(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS to 1000.0))
            request.observe(PerfEventKind.PROTOCOL_PROGRESS, mapOf("upstream_events" to 1.0))
            assertTrue(fixture.points(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS).isEmpty())
            request.finish(OutcomeTag.CANCELLED.wire, mapOf(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS to 0.0))
            assertEquals(1000.0, fixture.total(PerfKeys.ARRIVAL_TO_FIRST_CLIENT_BYTE_MS))
            assertTrue(fixture.points("arrival_to_first_meaningful_content_ms").isEmpty())
        }
    }
}
