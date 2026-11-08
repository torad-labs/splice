package splice.usage.perf.query

import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.observation.PerfAggregatePoint
import splice.core.perf.observation.PerfAggregateSnapshot
import splice.core.perf.observation.PerfCoverage
import splice.core.perf.observation.PerfHistory
import splice.core.perf.observation.PerfHistoryRead
import splice.core.perf.observation.PerfIdentity
import splice.core.perf.observation.PerfIssue
import splice.core.perf.observation.PerfScope
import splice.core.perf.observation.PerfSinkStatus
import splice.core.util.JsonScalars
import splice.core.util.PerfWorkBudget
import splice.usage.perf.PerfRowsSource

class PerfQueryAggregateTest {
    private val identity = PerfIdentity("boot", "b", "v", "c")
    private val scope = PerfScope(head = "h")

    @Test
    fun `cumulative minute replacements do not add repeated checkpoints and wall interval controls rates`() {
        val result = service(listOf(snapshot(2), snapshot(3))).summary(PerfQuery(0, 120_000, source = "history"))
        assertEquals("0.025", JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `ordinary relative window excludes partial bucket edges rather than leaking outside observations`() {
        val result = service(listOf(snapshot(3))).summary(PerfQuery(1_000, 61_000, source = "history"))
        assertNull(JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        assertEquals("60000", JsonScalars.str(result, "requested_duration_ms"))
        assertTrue(result["coverage"].toString().contains("partial_bucket_edges_excluded"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `raw filter cannot silently become an all project aggregate`() {
        val result = service(
            listOf(snapshot(3)),
        ).summary(PerfQuery(0, 120_000, mapOf("session" to "one"), source = "history"))
        assertNull(JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        assertTrue(result.toString().contains("aggregate_filter_unsupported"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `live source coverage survives query projection and partial edges still prevent exact completeness`() {
        val live = snapshot(3)
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun live(): PerfAggregateSnapshot = live
        })
        val result = service.summary(PerfQuery(0, 120_000, source = "live"))
        assertEquals("120000", JsonScalars.str(result, "observed_duration_ms"))
        assertEquals("true", JsonScalars.str(result.getValue("coverage").jsonObject, "complete"))
        assertEquals("ok", JsonScalars.str(result, "status"))
        PerfWorkBudget.release(result)
        val edge = service.summary(PerfQuery(1_000, 61_000, source = "live"))
        assertEquals("60000", JsonScalars.str(edge, "observed_duration_ms"))
        assertEquals("false", JsonScalars.str(edge.getValue("coverage").jsonObject, "complete"))
        assertTrue(edge.toString().contains("partial_bucket_edges_excluded"))
        PerfWorkBudget.release(edge)
    }

    @Test
    fun `bounded retention issue only marks overlapping aggregate query windows partial`() {
        val retention = PerfIssue(
            "retention_expired",
            "accumulator",
            detail = "Intervals before 60000 expired",
            fromMs = 0,
            untilMs = 60_000,
        )
        val source = service(
            listOf(snapshot(2, issue = retention), snapshot(3, fromMs = 60_000, issue = retention)),
            coverageIssues = listOf(retention),
        )

        val current = source.summary(PerfQuery(60_000, 120_000, source = "history"))
        assertEquals("ok", JsonScalars.str(current, "status"))
        assertFalse(current.toString().contains("retention_expired"))
        PerfWorkBudget.release(current)

        val overlapping = source.summary(PerfQuery(0, 60_000, source = "history"))
        assertEquals("partial", JsonScalars.str(overlapping, "status"))
        assertTrue(overlapping.toString().contains("retention_expired"))
        PerfWorkBudget.release(overlapping)
    }

    private fun snapshot(
        count: Long,
        fromMs: Long = 0,
        issue: PerfIssue? = null,
    ): PerfAggregateSnapshot = PerfAggregateSnapshot(
        identity,
        fromMs,
        fromMs + 60_000,
        listOf(
            PerfAggregatePoint(
                "requests_finished",
                scope,
                fromMs,
                fromMs + 60_000,
                count,
                count.toDouble(),
                1.0,
                1.0,
            ),
        ),
        PerfCoverage(0, 120_000, 120_000, listOfNotNull(issue)),
    )

    private fun service(
        snapshots: List<PerfAggregateSnapshot>,
        coverageIssues: List<PerfIssue> = emptyList(),
    ): PerfQueryService =
        PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun history(): PerfHistory = object : PerfHistory {
                override fun append(snapshot: PerfAggregateSnapshot): Boolean = false
                override fun read(fromMs: Long, untilMs: Long): PerfHistoryRead = PerfHistoryRead(
                    snapshots,
                    PerfCoverage(fromMs, untilMs, untilMs - fromMs, coverageIssues),
                    60_000,
                )
                override fun status(): PerfSinkStatus = PerfSinkStatus("test", 0, 0, 0, 0, 0, 0, null, null)
            }
        })
}
