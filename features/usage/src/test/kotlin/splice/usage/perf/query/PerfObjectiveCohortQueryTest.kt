package splice.usage.perf.query

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfObjectiveSet
import splice.core.perf.observation.PerfScope
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRowsSource

class PerfObjectiveCohortQueryTest {
    private val objective = PerfObjectiveSet("fast", "r1", completionMs = 100.0)
    private val query = PerfQuery(0, 60_000, source = "live", objectiveId = "fast")

    @Test
    fun `named objective applies its stored cohort before folding and rejects conflicting selectors`() {
        val service = service(mapOf("model" to "selected"))
        val result = service.summary(query)
        assertEquals(
            "selected",
            JsonScalars.str(result.getValue("query").jsonObject.getValue("filters").jsonObject, "model"),
        )
        assertEquals("2", JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"))
        PerfWorkBudget.release(result)
        val conflict = service.summary(query.copy(filters = mapOf("model" to "other")))
        assertEquals("error", JsonScalars.str(conflict, "status"))
        assertTrue("rates" !in conflict)
        PerfWorkBudget.release(conflict)
    }

    @Test
    fun `unknown named cohort never broadens while direct explicit objective remains supported`() {
        val service = service(null)
        val missing = service.summary(query)
        assertEquals("error", JsonScalars.str(missing, "status"))
        PerfWorkBudget.release(missing)
        val direct = service.summary(
            query.copy(
                objectiveId = null,
                objective = objective,
                filters = mapOf("model" to "selected"),
            ),
        )
        assertEquals("2", JsonScalars.str(direct.getValue("counts").jsonObject, "arrivals"))
        PerfWorkBudget.release(direct)
    }

    private fun service(cohort: Map<String, String>?): PerfQueryService = PerfQueryService(object : PerfQuerySources {
        override fun heads(): Set<String> = setOf("h")
        override fun rows(head: String): PerfRowsSource? = null
        override fun objective(id: String): PerfObjectiveSet = objective
        override fun objectiveCohort(id: String): Map<String, String>? = cohort
        override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
            PerfIdentity("boot", "build", "1", "rev"),
            0,
            60_000,
            listOf(point("selected", 2), point("other", 5)),
            PerfCoverage(0, 60_000, 60_000),
        )
    })

    private fun point(model: String, count: Long): PerfAggregatePoint = PerfAggregatePoint(
        "requests_arrived",
        PerfScope(head = "h", model = model),
        0,
        60_000,
        count,
        count.toDouble(),
        1.0,
        1.0,
    )
}
