package splice.dialect.anthropic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnBill
import splice.core.perf.PerfKeys
import splice.core.turn.UsageMeasure

class PassthroughUsageMeasurementTest {
    @Test
    fun `absent and null cache components remain unknown in inclusive input without changing billing`() {
        listOf("{}", """{"cache_read_input_tokens":null,"cache_creation_input_tokens":null}""").forEach { fields ->
            val usage = PassthroughUsage()
            usage.harvestUsage(Json.parseToJsonElement("""{"input_tokens":10}""").jsonObject)
            usage.harvestUsage(Json.parseToJsonElement(fields).jsonObject)
            val value = usage.toUsage()
            assertTrue(value.measured.isEmpty())
            assertEquals(10L, value.inputTokens)
            assertEquals(0L, TurnBill.counters(value)[PerfKeys.CACHE_WRITE_TOKENS])
        }
    }

    @Test
    fun `explicit zero cache fields and later output form a complete measured canonical bill`() {
        val usage = PassthroughUsage()
        usage.harvestUsage(
            Json.parseToJsonElement(
                """{"input_tokens":10,"cache_read_input_tokens":0,"cache_creation_input_tokens":0,""" +
                    """"cache_creation":{"ephemeral_1h_input_tokens":0}}""",
            ).jsonObject,
        )
        usage.harvestUsage(Json.parseToJsonElement("""{"output_tokens":5}""").jsonObject)
        val measured = usage.toUsage()
        assertEquals(UsageMeasure.entries.toSet(), measured.measured)
        assertEquals(10L, measured.inputTokens)
        assertEquals(5L, measured.outputTokens)
        usage.harvestUsage(Json.parseToJsonElement("""{"cache_creation_input_tokens":2}""").jsonObject)
        assertFalse(UsageMeasure.CACHE_WRITE_1H in usage.toUsage().measured)
        assertEquals(12L, usage.toUsage().inputTokens)
    }
}
