package splice.usage.perf.query

import kotlinx.serialization.json.jsonArray
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
    fun `a minute the live recorder still holds is counted once, from its live copy`() {
        val live = snapshot(3, fromMs = 60_000)
        // The files hold both minutes, the second as its earlier checkpoint.
        val stored = mapOf(0L to 3L, 60_000L to 2L)
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun live(): PerfAggregateSnapshot = live
            override fun history(): PerfHistory = object : PerfHistory {
                override fun append(snapshot: PerfAggregateSnapshot): Boolean = false

                // One coarsened bucket, as a read of more than a few minutes returns: it starts where the read does.
                override fun read(fromMs: Long, untilMs: Long): PerfHistoryRead {
                    val count = stored.filterKeys { it >= fromMs && it < untilMs }.values.sum()
                    val coverage = PerfCoverage(fromMs, untilMs, untilMs - fromMs)
                    val sum = count.toDouble()
                    val point = PerfAggregatePoint("requests_finished", scope, fromMs, untilMs, count, sum, 1.0, 1.0)
                    val bucket = PerfAggregateSnapshot(identity, fromMs, untilMs, listOf(point), coverage)
                    return PerfHistoryRead(listOf(bucket), coverage, untilMs - fromMs)
                }
                override fun status(): PerfSinkStatus = PerfSinkStatus("test", 0, 0, 0, 0, 0, 0, null, null)
            }
        })

        val result = service.summary(PerfQuery(0, 120_000, source = "auto"))

        assertEquals("0.05", JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        assertEquals("120000", JsonScalars.str(result, "observed_duration_ms"))
        PerfWorkBudget.release(result)
    }

    // The daemon restarted partway through the second minute. That minute's file holds what the earlier boot observed
    // in it and this boot's own earlier checkpoint; the live recorder holds this boot's newer copy.
    @Test
    fun `a period across a restart counts what the earlier boot observed in the minute the daemon restarted`() {
        val before = PerfIdentity("boot-before", "b", "v", "c")
        val live = snapshot(3, fromMs = 60_000)
        val files = listOf(
            snapshot(1, by = before),
            snapshot(2, fromMs = 60_000, by = before),
            snapshot(1, fromMs = 60_000),
        )
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun live(): PerfAggregateSnapshot = live
            override fun history(): PerfHistory = object : PerfHistory {
                override fun append(snapshot: PerfAggregateSnapshot): Boolean = false
                override fun read(fromMs: Long, untilMs: Long): PerfHistoryRead = PerfHistoryRead(
                    files.filter { it.fromMs >= fromMs && it.fromMs < untilMs },
                    PerfCoverage(fromMs, untilMs, untilMs - fromMs),
                    60_000,
                )
                override fun status(): PerfSinkStatus = PerfSinkStatus("test", 0, 0, 0, 0, 0, 0, null, null)
            }
        })

        val result = service.summary(PerfQuery(0, 120_000, source = "auto"))

        // 1 and 2 by the earlier boot and 3 by this one, whose file checkpoint of that minute is not counted again:
        // 6 in 120 s
        assertEquals("0.05", JsonScalars.str(result.getValue("rates").jsonObject, "completions_per_s"))
        PerfWorkBudget.release(result)
    }

    @Test
    fun `a series too long for one reply returns every count, in intervals widened until it fits`() {
        val until = 40 * 60_000L
        // 4,000 entries of a minute each pass the reply's 523,776 characters; two minutes an entry fit.
        val points = (0 until 40).flatMap { minute ->
            (0 until 100).map { model ->
                val from = minute * 60_000L
                val each = PerfScope(head = "h", model = "model-$model")
                PerfAggregatePoint("requests_finished", each, from, from + 60_000, 1, 1.0, 1.0, 1.0)
            }
        }
        val live = PerfAggregateSnapshot(identity, 0, until, points, PerfCoverage(0, until, until))
        val service = PerfQueryService(object : PerfQuerySources {
            override fun heads(): Set<String> = setOf("h")
            override fun rows(head: String): PerfRowsSource? = null
            override fun live(): PerfAggregateSnapshot = live
        })

        val result = service.series(PerfQuery(0, until, source = "live"))

        val series = result.getValue("series").jsonArray
        assertEquals("120000", JsonScalars.str(result, "resolution_ms"))
        assertEquals(2_000, series.size)
        assertEquals(
            4_000L,
            series.sumOf {
                val finished = it.jsonObject.getValue("metrics").jsonObject.getValue("requests_finished")
                checkNotNull(JsonScalars.str(finished.jsonObject, "count")).toLong()
            },
        )
        PerfWorkBudget.release(result)
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
        by: PerfIdentity = identity,
    ): PerfAggregateSnapshot = PerfAggregateSnapshot(
        by,
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
