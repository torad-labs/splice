package splice.app.instrumentation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RetainedQueryClassificationTest {
    @Test
    fun `history query counts only a nonempty object series as usable`() {
        assertFalse(isUsableRetainedHistorySeries(null))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(emptyList())))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(JsonPrimitive("bad")))))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(buildJsonObject { put("from_ms", 1L) }))))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(historyPoint(2L, 1L)))))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(historyPoint(1L, 2L, includeScope = false)))))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(historyPoint(1L, 2L, includeMetrics = false)))))
        assertFalse(isUsableRetainedHistorySeries(JsonArray(listOf(historyPoint(1L, 2L, includeCoverage = false)))))
        assertTrue(isUsableRetainedHistorySeries(JsonArray(listOf(historyPoint(1L, 2L)))))
    }

    private fun historyPoint(
        from: Long,
        until: Long,
        includeScope: Boolean = true,
        includeMetrics: Boolean = true,
        includeCoverage: Boolean = true,
    ) = buildJsonObject {
        put("from_ms", from)
        put("until_ms", until)
        put("scope", if (includeScope) scopePoint() else buildJsonObject { })
        put("metrics", if (includeMetrics) metricPoint() else buildJsonObject { })
        put("coverage", if (includeCoverage) coveragePoint(from, until) else buildJsonObject { })
    }

    private fun scopePoint() = buildJsonObject {
        put("head", "bench")
        put("model", "fixture")
        put("transport", "sse")
        put("work", "client")
        put("outcome", "good")
        put("local", JsonNull)
        put("compact", JsonNull)
        put("streaming", JsonNull)
    }

    private fun metricPoint() = buildJsonObject {
        put(
            "arrival_to_terminal_ms",
            buildJsonObject {
                put("count", 1L)
                put("sum", 20.0)
                put("min", 20.0)
                put("max", 20.0)
                put("p50", 50.0)
                put("p95", 50.0)
                put("p99", 50.0)
                put("bounds_ms", JsonArray(listOf(JsonPrimitive(50.0))))
                put("buckets", JsonArray(listOf(JsonPrimitive(1L), JsonPrimitive(0L))))
                put("approximation", "upper_inclusive_bucket_bound; overflow_quantile_unknown")
            },
        )
    }

    private fun coveragePoint(from: Long, until: Long) = buildJsonObject {
        put("from_ms", from)
        put("until_ms", until)
        put("observed_ms", until - from)
        put("complete", true)
        put("issues", JsonArray(emptyList()))
        put("sinks", JsonArray(emptyList()))
    }

    @Test
    fun `only a populated retention-limited page is expected incomplete`() {
        assertEquals(
            BenchmarkRetainedCursorOutcome.EXPECTED_RETENTION_INCOMPLETE,
            classifyBenchmarkRetainedCursor(
                BenchmarkRetainedCursorRead(
                    "incomplete",
                    listOf("evidence_prior_loss_unknown", "evidence_retention_expired"),
                    page = BenchmarkRetainedCursorPage(32, 120, null, 0, true),
                    coverage = BenchmarkRetainedCursorCoverage(
                        "incomplete",
                        "unknown_before_retained_segments",
                    ),
                ),
            ),
        )
        for (issue in listOf("evidence_prior_loss_unknown", "evidence_retention_expired")) {
            assertEquals(
                BenchmarkRetainedCursorOutcome.EXPECTED_RETENTION_INCOMPLETE,
                classifyBenchmarkRetainedCursor(
                    BenchmarkRetainedCursorRead(
                        "incomplete",
                        listOf(issue),
                        page = BenchmarkRetainedCursorPage(32, 120, null, 0, true),
                        coverage = BenchmarkRetainedCursorCoverage(
                            "incomplete",
                            "unknown_before_retained_segments",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun `complete cursor page requires consistent counts and clean coverage`() {
        assertEquals(
            BenchmarkRetainedCursorOutcome.COMPLETE,
            classifyBenchmarkRetainedCursor(
                BenchmarkRetainedCursorRead(
                    "complete",
                    emptyList(),
                    page = BenchmarkRetainedCursorPage(20, 20, 20, 0, false),
                    coverage = BenchmarkRetainedCursorCoverage(
                        "retained_snapshot",
                        "unknown_before_retained_segments",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `unexpected cursor outcomes remain refusals`() {
        val base = listOf("evidence_prior_loss_unknown", "evidence_retention_expired")
        fun classify(
            issues: List<String>? = base,
            returned: Int? = 32,
            known: Long? = 120,
            total: Long? = null,
            malformed: Long? = 0,
            hasNext: Boolean? = true,
            status: String? = "incomplete",
            coverageStatus: String? = "incomplete",
            historicalCompleteness: String? = "unknown_before_retained_segments",
        ) = classifyBenchmarkRetainedCursor(
            BenchmarkRetainedCursorRead(
                status,
                issues,
                BenchmarkRetainedCursorPage(returned, known, total, malformed, hasNext),
                BenchmarkRetainedCursorCoverage(coverageStatus, historicalCompleteness),
            ),
        )

        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(issues = listOf("cursor_busy")))
        assertEquals(
            BenchmarkRetainedCursorOutcome.REFUSED,
            classify(issues = listOf("evidence_retention_expired", "evidence_retention_expired")),
        )
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(returned = 0))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(known = 8))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(malformed = 1))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(hasNext = false))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(status = "busy"))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(total = 120))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(historicalCompleteness = null))
        assertEquals(BenchmarkRetainedCursorOutcome.REFUSED, classify(coverageStatus = "retained_snapshot"))
    }
}
