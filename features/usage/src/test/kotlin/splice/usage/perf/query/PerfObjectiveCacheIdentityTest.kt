package splice.usage.perf.query

import kotlinx.serialization.json.JsonObject
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
import splice.core.util.WallClock
import splice.usage.perf.PerfRowsSource

class PerfObjectiveCacheIdentityTest {
    @Test
    fun `same named query inside cache lifetime follows current revision cohort and conflicts`() {
        val sources = MutableObjectives()
        val service = PerfQueryService(sources, clock = WallClock { 100_000 })
        val query = PerfQuery(0, 60_000, source = "live", objectiveId = "fast")
        checkResult(service.summary(query), "r1", "selected", "2")
        sources.objective = sources.objective.copy(revision = "r2")
        checkResult(service.summary(query), "r2", "selected", "2")
        sources.cohort = mapOf("model" to "other")
        checkResult(service.summary(query), "r2", "other", "5")
        val explicit = query.copy(filters = mapOf("model" to "other"))
        checkResult(service.summary(explicit), "r2", "other", "5")
        sources.cohort = mapOf("model" to "selected")
        val conflict = service.summary(explicit)
        try {
            assertEquals("error", JsonScalars.str(conflict, "status"))
            assertTrue("counts" !in conflict)
        } finally {
            PerfWorkBudget.release(conflict)
        }
    }

    private fun checkResult(result: JsonObject, revision: String, model: String, arrivals: String) {
        try {
            val selection = result.getValue("query").jsonObject
            assertEquals(revision, JsonScalars.str(selection, "objective_revision"))
            assertEquals(model, JsonScalars.str(selection.getValue("filters").jsonObject, "model"))
            assertEquals(arrivals, JsonScalars.str(result.getValue("counts").jsonObject, "arrivals"))
        } finally {
            PerfWorkBudget.release(result)
        }
    }

    private class MutableObjectives : PerfQuerySources {
        var objective = PerfObjectiveSet("fast", "r1", completionMs = 100.0)
        var cohort = mapOf("model" to "selected")
        override fun heads(): Set<String> = setOf("h")
        override fun rows(head: String): PerfRowsSource? = null
        override fun objective(id: String): PerfObjectiveSet = objective
        override fun objectiveCohort(id: String): Map<String, String> = cohort
        override fun live(): PerfAggregateSnapshot = PerfAggregateSnapshot(
            PerfIdentity("boot", "build", "1", "rev"),
            0,
            60_000,
            listOf(point("selected", 2), point("other", 5)),
            PerfCoverage(0, 60_000, 60_000),
        )
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
}
