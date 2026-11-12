package splice.core.perf.observation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.OutcomeTag

internal class PerfObjectivesTest {
    private val objective = PerfObjectiveSet("client", "one", firstContentMs = 1000.0, completionMs = 2000.0)
    private val values = mapOf("arrival_to_first_meaningful_content_ms" to 1000.0, "arrival_to_terminal_ms" to 2000.0)

    @Test
    fun `objectives distinguish unfinished failed missing and successfully bounded observations`() {
        assertEquals(PerfObjectiveVerdict.PENDING, PerfObjectives.evaluate(null, values, objective))
        assertEquals(PerfObjectiveVerdict.BAD, PerfObjectives.evaluate(OutcomeTag.CANCELLED.wire, values, objective))
        assertEquals(PerfObjectiveVerdict.BAD, PerfObjectives.evaluate(OutcomeTag.AT_CAPACITY.wire, values, objective))
        assertEquals(PerfObjectiveVerdict.UNKNOWN, PerfObjectives.evaluate(OutcomeTag.OK.wire, emptyMap(), objective))
        assertEquals(PerfObjectiveVerdict.GOOD, PerfObjectives.evaluate(OutcomeTag.OK.wire, values, objective))
        assertEquals(PerfObjectiveVerdict.GOOD, PerfObjectives.evaluate("good", values, objective))
        assertEquals(
            PerfObjectiveVerdict.UNKNOWN,
            PerfObjectives.evaluate(OutcomeTag.OK.wire, values, PerfObjectiveSet("empty", "one")),
        )
    }
}
