package splice.dialect.responses.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys

class ResponsesUsageObservationTest {
    private val harvest = ResponsesHarvest()

    @Test
    fun `a missing response usage object produces no observed billing counters`() {
        assertTrue(TurnBill.counters(harvest.usageFrom(null)).isEmpty())
        assertTrue(TurnBill.counters(harvest.usageFrom(Json.parseToJsonElement("{}").jsonObject)).isEmpty())
    }

    @Test
    fun `an input-only failed response never invents an output observation`() {
        val usage = harvest.usageFrom(Json.parseToJsonElement("""{"usage":{"input_tokens":100}}""").jsonObject)
        val counters = TurnBill.counters(usage)
        assertEquals(100L, counters[PerfKeys.IN_TOKENS])
        assertTrue(PerfKeys.OUT_TOKENS !in counters)
    }

    @Test
    fun `reported zero response counts remain observed zeros`() {
        val usage = harvest.usageFrom(
            Json.parseToJsonElement("""{"usage":{"input_tokens":0,"output_tokens":0}}""").jsonObject,
        )
        val counters = TurnBill.counters(usage)
        assertEquals(0L, counters[PerfKeys.IN_TOKENS])
        assertEquals(0L, counters[PerfKeys.OUT_TOKENS])
    }
}
