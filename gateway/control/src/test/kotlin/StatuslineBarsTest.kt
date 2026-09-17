// NEW: the plan-usage segments of the daemon's status line (see StatuslineBars): effort beside
// the model, session spend, 5h and 7d bars. Drawn from Claude Code's own rate_limits when the
// blob carries them, else from the head's tracked quota; the reset time appears only once a bar
// is worth acting on.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.control.HeadPerfSkipSource
import splice.control.HeadUsageSource
import splice.control.QuotaView
import splice.control.QuotaWindowView
import splice.control.SessionCostSource
import splice.control.StatuslineBars
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
    fun `on a pooled line the selected account's window wins and the client fills a missing one`() {
        val root = Json.parseToJsonElement(
            """{"rate_limits":{"five_hour":{"used_percentage":14},"seven_day":{"used_percentage":42}}}""",
        ).jsonObject
        val tracked = QuotaView(QuotaWindowView(72, null), null, "pro")
        val pooled = StatuslineBars().limitSegments(root, tracked, quotaFirst = true).map { it.replace(ansi, "") }
        assertEquals(listOf("5h ██████░░ 72%", "7d ███░░░░░ 42%"), pooled)
        val plain = StatuslineBars().limitSegments(root, tracked).map { it.replace(ansi, "") }
        assertEquals(listOf("5h █░░░░░░░ 14%", "7d ███░░░░░ 42%"), plain, "unpooled: the client's own headers win")
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

    /** V4-45, the last hop to a human. The cost reader DROPS a perf row it cannot parse — a torn
     *  append leaves a length-extended run of NULs — so the figure is summed from fewer turns than
     *  the session ran and reads LOW. The count has been computed and reachable since this row's
     *  reader half landed and nothing rendered it, which is the same silence one layer up.
     *
     *  Both halves are pinned here, and the nothing-dropped half is the one that matters most:
     *  NEVER-BELOW-STATUS-QUO means a healthy head's bar must be byte-identical to the one it drew
     *  before this change, not merely similar. */
    @Test
    fun `dropped perf rows mark splice's own figure and carry the count`() {
        val root = Json.parseToJsonElement("""{"cost":{"total_cost_usd":61.44}}""").jsonObject
        val bars = StatuslineBars()

        val clean = bars.costSegment(root, computed = 12.5, droppedRows = 0L)?.replace(ansi, "")
        assertEquals("$12.50", clean, "nothing dropped, nothing said — byte-identical to before")

        val short = bars.costSegment(root, computed = 12.5, droppedRows = 3L)?.replace(ansi, "")
        assertEquals("≥$12.50 ⚠3", short, "the true spend is AT LEAST this, and 3 rows were unreadable")
    }

    /** THE FALSE-ALARM GUARD, and it is not a technicality. When the head declares no rates the bar
     *  falls back to Claude Code's own `total_cost_usd`, which splice did not compute and the perf
     *  reader had no part in. Dropped rows make OUR figure low and say nothing whatever about that
     *  one, so a marker beside it would be an alarm about a number it does not describe. */
    @Test
    fun `a dropped row never marks the client's own number`() {
        val root = Json.parseToJsonElement("""{"cost":{"total_cost_usd":61.44}}""").jsonObject
        val fallback = StatuslineBars().costSegment(root, computed = null, droppedRows = 9L)
        assertEquals("$61.44", fallback?.replace(ansi, ""), "only OUR figure can be short, so only ours is qualified")
    }

    /** THE HOP ITSELF. The two arms above prove the renderer draws the marker when handed a count;
     *  this proves the count ARRIVES — PerfStats to PerfStatsSource to the route's checked cast to
     *  this constructor to the segment. Without it both arms above stay green against a renderer
     *  wired to nothing, which is exactly the state this row found the counter in. */
    @Test
    fun `the dropped-row count reaches the rendered line through the renderer's perf source`() {
        val usage = HeadUsageSource { UsageView(0L, 0, null, null) }
        val renderer = StatuslineRenderer(
            label = "grok",
            sessionCost = SessionCostSource { _, _ -> 12.5 },
            perfSkips = HeadPerfSkipSource { 3L },
        )
        val line = renderer.render(
            """{"model":{"id":"grok-4.6"},"cost":{"total_cost_usd":61.44}}""",
            usage,
            warnPct = 0,
            warnTokens5h = 0L,
            sessionId = "a6b15bd7-dead-beef",
        ).replace(ansi, "")

        assertTrue("≥$12.50 ⚠3" in line, "the count must reach the bar, not sit in a reachable method: $line")
        assertFalse("$61.44" in line, "splice's own figure still wins over the client's: $line")
    }

    @Test
    fun `no rate_limits and no quota draws no bars, and a zero cost draws no spend`() {
        val line = render("""{"model":{"id":"mock"},"cost":{"total_cost_usd":0}}""")
        assertFalse("5h" in line || "7d" in line || "$" in line, line)
        assertEquals("● mock", line.trim())
    }
}
