package splice.usage.perf.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.util.JsonScalars
import splice.core.util.JsonWire
import splice.core.util.PerfWorkBudget
import splice.core.util.WallClock
import splice.usage.perf.PerfRow
import splice.usage.perf.PerfRowsSource
import splice.usage.perf.PerfRowsWindow

class PerfLegacySummaryWireTest {
    private val legacyRows = listOf(
        PerfRow(100, "ok", mapOf(PerfKeys.TOTAL to 50L)),
        PerfRow(125, "error:upstream-failed", mapOf(PerfKeys.TOTAL to 75L)),
        PerfRow(150, "ok", mapOf(PerfKeys.LOCAL_STEP to 1L, PerfKeys.TOTAL to 0L)),
    )

    @Test
    fun `selectors absent from legacy rows serialize an empty cohort with an undefined failure share`() {
        listOf("transport" to "http", "streaming" to "true", "streaming" to "false").forEach { selector ->
            val legacy = summary(PerfQuery(100, 200, filters = mapOf("head" to "h", selector)))
            assertEquals("0", JsonScalars.str(legacy, "count"))
            assertEquals("0", JsonScalars.str(legacy, "row_count"))
            assertEquals("0", JsonScalars.str(legacy, "local_steps"))
            assertEquals("true", JsonScalars.str(legacy, "empty"))
            assertEmptyMetrics(legacy)
        }
    }

    @Test
    fun `completed idle window serializes an undefined failure share instead of zero traffic quality`() {
        val legacy = summary(PerfQuery(200, 300))
        assertEquals("0", JsonScalars.str(legacy, "count"))
        assertEquals("0", JsonScalars.str(legacy, "row_count"))
        assertEquals("true", JsonScalars.str(legacy, "empty"))
        assertEmptyMetrics(legacy)
    }

    @Test
    fun `local only rows retain their count without inventing a turn failure share`() {
        val legacy = summary(PerfQuery(100, 200, filters = mapOf("local" to "true")))
        assertEquals("0", JsonScalars.str(legacy, "count"))
        assertEquals("1", JsonScalars.str(legacy, "local_steps"))
        assertEquals("1", JsonScalars.str(legacy, "row_count"))
        assertEquals("false", JsonScalars.str(legacy, "empty"))
        assertEmptyMetrics(legacy)
    }

    @Test
    fun `nonempty legacy turns retain their counts and finite failure shares on the wire`() {
        val legacy = summary(PerfQuery(100, 200))
        assertEquals("2", JsonScalars.str(legacy, "count"))
        assertEquals("1", JsonScalars.str(legacy, "local_steps"))
        assertEquals("3", JsonScalars.str(legacy, "row_count"))
        assertEquals("0.5", JsonScalars.str(legacy, "failure_share"))
        assertEquals("0.5", JsonScalars.str(legacy.getValue("failure_shares").jsonObject, "error:upstream-failed"))
        assertEquals("2", JsonScalars.str(legacy.getValue("total_ms").jsonObject, "count"))
    }

    private fun assertEmptyMetrics(legacy: JsonObject) {
        assertEquals(JsonNull, legacy.getValue("failure_share"))
        assertEquals(JsonObject(emptyMap()), legacy.getValue("failure_shares"))
        assertEquals(JsonObject(emptyMap()), legacy.getValue("outcomes"))
        assertEquals(JsonObject(emptyMap()), legacy.getValue("models"))
        assertFalse("total_ms" in legacy)
    }

    private fun summary(query: PerfQuery): JsonObject {
        val sources = object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource = PerfRowsSource { PerfRowsWindow(legacyRows) }
        }
        return PerfQueryService(sources, clock = WallClock { 1_000 }).use { service ->
            val result = service.summary(query)
            try {
                Json.parseToJsonElement(JsonWire.bytes(result).decodeToString()).jsonObject
                    .getValue("legacy").jsonObject.getValue("h").jsonObject
            } finally {
                PerfWorkBudget.release(result)
            }
        }
    }
}
