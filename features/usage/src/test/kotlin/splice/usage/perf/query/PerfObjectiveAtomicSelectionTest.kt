package splice.usage.perf.query

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRowsSource

class PerfObjectiveAtomicSelectionTest {
    @Test
    fun `named query uses one coherent selection despite incompatible legacy lookup generations`() {
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun objective(id: String): PerfObjectiveSet = PerfObjectiveSet(id, "old", completionMs = 100.0)
            override fun objectiveCohort(id: String): Map<String, String> = mapOf("model" to "new")
            override fun objectiveSelection(id: String): PerfObjectiveSelection = PerfObjectiveSelection(
                PerfObjectiveSet(id, "coherent", completionMs = 200.0),
                mapOf("model" to "selected"),
            )
        })
        val result = service.summary(PerfQuery(0, 60_000, objectiveId = "latency"))
        try {
            val query = result.getValue("query").jsonObject
            assertEquals("coherent", JsonScalars.str(query, "objective_revision"))
            assertEquals("selected", JsonScalars.str(query.getValue("filters").jsonObject, "model"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    @Test
    fun `separate legacy lookups cannot silently claim a coherent named selection`() {
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun objective(id: String): PerfObjectiveSet = PerfObjectiveSet(id, "old", completionMs = 100.0)
            override fun objectiveCohort(id: String): Map<String, String> = emptyMap()
        })
        val result = service.summary(PerfQuery(0, 60_000, objectiveId = "latency"))
        try { assertEquals("error", JsonScalars.str(result, "status")) } finally { PerfWorkBudget.release(result) }
    }
}
