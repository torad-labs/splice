package splice.dialect.responses.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ModelRates
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
    fun `nested cached zero without input remains an observed cache bucket`() {
        val response = Json.parseToJsonElement("""{"usage":{"input_tokens_details":{"cached_tokens":0}}}""")
        assertEquals(mapOf(PerfKeys.CACHED_TOKENS to 0L), TurnBill.counters(harvest.usageFrom(response.jsonObject)))
    }

    @Test
    fun `flat cached zero without input remains an observed cache bucket`() {
        val response = Json.parseToJsonElement("""{"usage":{"cache_read_input_tokens":0}}""")
        assertEquals(mapOf(PerfKeys.CACHED_TOKENS to 0L), TurnBill.counters(harvest.usageFrom(response.jsonObject)))
    }

    @Test
    fun `an input-only failed response never invents an output observation`() {
        val usage = harvest.usageFrom(Json.parseToJsonElement("""{"usage":{"input_tokens":100}}""").jsonObject)
        val counters = TurnBill.counters(usage)
        assertEquals(100L, counters[PerfKeys.IN_TOKENS])
        assertTrue(PerfKeys.OUT_TOKENS !in counters)
        assertEquals(0.0002, TurnBill.lowerBoundUsd(counters, ModelRates(2.0, 0.2, 10.0))!!, 1e-12)
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
