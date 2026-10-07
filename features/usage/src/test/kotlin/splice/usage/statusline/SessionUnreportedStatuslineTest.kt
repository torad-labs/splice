package splice.usage.statusline

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val renderer = StatuslineRenderer(label = "synthetic", sessionCost = SessionCost(source))
        return renderer.render(
            """{"model":{"id":"synthetic"},"cost":{"total_cost_usd":9.99}}""",
            null,
            warnPct = 0,
            warnTokens5h = 0,
            sessionId = "synthetic",
        )
    }

    @Test
    fun `a posted unreported turn renders known session spend as a lower bound`() {
        val total = PerfModelTotal(2, 100, 0, 0, 7, 0.5, unpricedTurns = 1, unreportedUsageTurns = 1)
        assertTrue(render(total).contains("≥"), "the rendered cost must label incomplete spend")
    }

    @Test
    fun `the persisted usage coverage count itself labels a lower bound`() {
        val total = PerfModelTotal(2, 100, 0, 0, 7, 0.5, unpricedTurns = 0, unreportedUsageTurns = 1)
        assertTrue(render(total).contains("≥"))
    }
}
