// NEW: the plan-usage segments of the daemon's status line (see StatuslineBars): effort beside
// the model, session spend, 5h and 7d bars. Drawn from Claude Code's own rate_limits when the
// blob carries them, else from the head's tracked quota; the reset time appears only once a bar
// is worth acting on.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.HeadUsageSource
import splice.control.QuotaView
import splice.control.QuotaWindowView
import splice.control.StatuslineRenderer
import splice.control.UsageView

class StatuslineBarsTest {

    private val ansi = Regex("\\[[0-9;]*m")

    private fun render(stdin: String, quota: QuotaView? = null): String {
        val usage = HeadUsageSource { UsageView(0L, 0, null, quota) }
        val line = StatuslineRenderer(label = "grok").render(stdin, usage, warnPct = 0, warnTokens5h = 0)
        return line.replace(ansi, "")
    }

    @Test
    fun `rate_limits in the blob become the 5h and 7d bars, with effort and spend beside the model`() {
        val line = render(
            """{"model":{"id":"grok-4.6","display_name":"Grok 4.6"},"effort":{"level":"high"},"cost":{"total_cost_usd":61.44},
                "rate_limits":{"five_hour":{"used_percentage":14,"resets_at":1788010000},"seven_day":{"used_percentage":42.4,"resets_at":1788500000}}}""",
        )
        assertTrue("Grok 4.6·high" in line, "effort rides beside the model: $line")
        assertTrue("$61.44" in line, "session spend: $line")
        assertTrue("5h █░░░░░░░ 14%" in line, "5h bar: $line")
        assertTrue("7d ███░░░░░ 42%" in line, "7d bar: $line")
        assertFalse("→" in line, "no reset time under 60%: $line")
    }

    @Test
    fun `the head's own quota fills the bars before any response carried headers, and 60 percent shows the reset`() {
        val quota = QuotaView(QuotaWindowView(72, 1_788_010_000L), QuotaWindowView(9, null), "pro")
        val line = render("""{"model":{"id":"gpt-5.6-sol"}}""", quota)
        assertTrue("5h ██████░░ 72%→" in line, "5h from the tracker with a reset time: $line")
        assertTrue("7d █░░░░░░░ 9%" in line, "7d from the tracker: $line")
    }

    @Test
    fun `no rate_limits and no quota draws no bars, and a zero cost draws no spend`() {
        val line = render("""{"model":{"id":"mock"},"cost":{"total_cost_usd":0}}""")
        assertFalse("5h" in line || "7d" in line || "$" in line, line)
        assertEquals("● mock", line.trim())
    }
}
