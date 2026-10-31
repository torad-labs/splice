package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.perf.PerfModelGaps
import splice.core.perf.PerfModelTotal
import splice.core.perf.PerfSessionTail
import splice.core.perf.PerfSessionTotal
import splice.usage.perf.HeadSessionPerfSource

class SessionUnreportedStatuslineTest {
    private fun render(model: PerfModelTotal): String {
        val source = object : HeadSessionPerfSource {
            override fun sessionTail(sessionId: String): PerfSessionTail = PerfSessionTail(emptyList(), null)
            override fun sessionTotal(sessionId: String): PerfSessionTotal =
                PerfSessionTotal(1L, mapOf("synthetic" to model))
        }
        val renderer = StatuslineRenderer(label = "synthetic", spend = StatuslineSpend(SessionCost(source)))
        return renderer.render(
            """{"model":{"id":"synthetic"},"cost":{"total_cost_usd":9.99}}""",
            null,
            StatuslineWarn(0, 0),
            sessionId = "synthetic",
        )
    }

    @Test
    fun `a posted unreported turn renders known session spend as a lower bound`() {
        val total = PerfModelTotal(2, 100, 0, 0, 7, 0.5, gaps = PerfModelGaps(1, 1))
        assertTrue(render(total).contains("≥"), "the rendered cost must label incomplete spend")
    }

    @Test
    fun `the persisted usage coverage count itself labels a lower bound`() {
        val total = PerfModelTotal(2, 100, 0, 0, 7, 0.5, gaps = PerfModelGaps(0, 1))
        assertTrue(render(total).contains("≥"))
    }
}
