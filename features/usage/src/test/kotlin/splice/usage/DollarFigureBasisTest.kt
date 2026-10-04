// NEW: V4-240, the behavioral half of kt-dollar-figure-single-source. Every surface that wall's census
// found printing a dollar figure (the status line's cost segment, the budget refusal, the budget
// warning) is driven here, and each figure must say what it is based on: an API-rate estimate
// ("API est. $0.85", "an estimated $0.85 in API cost"), or the operator's own budget ("$2.00 daily
// budget"). Anything else is a bare figure, which reads as a charge. And a figure Claude Code priced
// itself never renders under another vendor's model: that was the $0.85 a resumed Opus session left
// on the GPT-6-Sol line in rehearsal-resume-2.
package splice.usage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.usage.budgets.BudgetText
import splice.usage.budgets.DayTally
import splice.usage.quota.HeadUsageSource
import splice.usage.quota.UsageView
import splice.usage.statusline.SessionCostSource
import splice.usage.statusline.StatuslineRenderer

class DollarFigureBasisTest {

    private val ansi = Regex("\u001B\\[[0-9;]*m")
    private val usage = HeadUsageSource { UsageView(0L, 0, null, null) }

    private fun line(renderer: StatuslineRenderer, blob: String): String =
        renderer.render(blob, usage, warnPct = 0, warnTokens5h = 0L, sessionId = "s-1").replace(ansi, "")

    /** Strips every figure that carries its basis; any dollar figure left over is bare. */
    private fun assertEveryFigureHasItsBasis(text: String) {
        val rest = text.replace(ansi, "")
            .replace(Regex("""API est\. ≥?\$\d+\.\d{2}"""), "")
            .replace(Regex("""an estimated \$\d+\.\d{2} in API cost"""), "")
            .replace(Regex("""\$\d+\.\d{2} daily budget"""), "")
        assertFalse(Regex("""\$\s*\d""").containsMatchIn(rest), "a bare dollar figure in: $text")
    }

    @Test
    fun `splice's own figure on the status line says it is an API-rate estimate`() {
        val renderer = StatuslineRenderer(label = "codex", sessionCost = SessionCostSource { _, _ -> 0.85 })
        val text = line(renderer, """{"model":{"id":"gpt-6-sol"},"cost":{"total_cost_usd":3.1}}""")

        assertTrue(Regex("""\$\d""").containsMatchIn(text), "the figure itself still renders: $text")
        assertEveryFigureHasItsBasis(text)
    }

    @Test
    fun `a figure Claude Code priced itself never renders under another vendor's model`() {
        // No card on this head: the only figure in the blob is the client's, priced at Anthropic's card.
        val renderer = StatuslineRenderer(label = "codex", sessionCost = SessionCostSource { _, _ -> null })
        val text = line(renderer, """{"model":{"id":"gpt-6-sol"},"cost":{"total_cost_usd":0.85}}""")

        assertFalse("0.85" in text, "an Anthropic-priced figure under GPT-6-Sol: $text")
        assertTrue("no rate card" in text, "no card is said in words, not with a blank: $text")
    }

    @Test
    fun `the budget refusal and the budget warning say the day's spend is an estimate`() {
        val tally = DayTally(day = 0L).apply { add(2.5) }
        val refusal = BudgetText.refusal("codex", tally, limit = 2.0).message
        val warning = BudgetText.warning("codex", spent = 1.0, limit = 1.0)

        listOf(refusal, warning).forEach { text ->
            assertTrue(Regex("""\$\d""").containsMatchIn(text), "the figures themselves still render: $text")
            assertEveryFigureHasItsBasis(text)
        }
    }
}
