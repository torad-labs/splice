// NEW: Oct 10, 2026 review finding 3 — each model's week is its own. Anthropic's weekly_scoped rows carry
// their own resets_at, so a model week can roll while the account's week runs on. Both retention and the
// current-reading rule compared only the account's reset, which reported a model's pre-reset figure as its
// usage on a week that starts at zero: the Accounts page and the status line showed a spent Sonnet week
// minutes after Sonnet's week had refilled.
package splice.core.usage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val NOW_MS = 1_791_600_000_000L
private const val NOW_S = NOW_MS / 1_000

/** The account's week, running well past now. */
private const val ACCOUNT_WEEK_RESETS = NOW_S + 3 * 86_400L

class ModelWeekRetentionTest {

    private fun reading(
        observedAtMs: Long,
        models: List<ModelQuota> = emptyList(),
        weekResets: Long = ACCOUNT_WEEK_RESETS,
    ) = QuotaSnapshot(
        fiveHour = QuotaWindow(12.0, NOW_S + 3_600L, FIVE_HOURS_SECONDS),
        sevenDay = QuotaWindow(40.0, weekResets, SEVEN_DAYS_SECONDS),
        updatedAt = observedAtMs,
        models = models,
    )

    @Test
    fun `a model whose own week has rolled is not a current reading, while the account's week runs on`() {
        val rolled = ModelQuota("Sonnet", 90.0, resetsAt = NOW_S - 60L)
        val running = ModelQuota("Fable", 40.0, resetsAt = NOW_S + 2 * 86_400L)
        val accountWeekOnly = ModelQuota("Opus", 70.0)

        val current = reading(NOW_MS - 60_000L, listOf(rolled, running, accountWeekOnly)).currentAt(NOW_MS)

        assertEquals(
            listOf(running, accountWeekOnly),
            current?.models,
            "a rolled model week has nothing read since, so its figure is neither the old one nor zero",
        )
        assertEquals(40.0, current?.sevenDay?.usedPercent, "the account's own week is unaffected")
    }

    @Test
    fun `a header reading carries forward only the model weeks that have not rolled`() {
        val rolled = ModelQuota("Sonnet", 90.0, resetsAt = NOW_S - 60L)
        val running = ModelQuota("Fable", 40.0, resetsAt = NOW_S + 2 * 86_400L)
        val fromTheProbe = reading(NOW_MS - 600_000L, listOf(rolled, running))

        // A turn's header reading names no model weeks at all, so it keeps the probe's — the ones still
        // reading the week this reading was taken in.
        val kept = reading(NOW_MS).keepingModelsOf(fromTheProbe)

        assertEquals(listOf(running), kept.models)
    }

    @Test
    fun `a different account week carries nothing forward, as before`() {
        val earlier = reading(NOW_MS - 600_000L, listOf(ModelQuota("Fable", 40.0)), weekResets = NOW_S + 86_400L)

        val kept = reading(NOW_MS, weekResets = NOW_S + 8 * 86_400L).keepingModelsOf(earlier)

        assertEquals(emptyList<ModelQuota>(), kept.models, "the week rolled: the model rows belong to the old one")
    }

    @Test
    fun `a surface that draws model weeks drops the one whose own week has passed, with the account week current`() {
        val rolled = ModelQuota("Sonnet", 90.0, resetsAt = NOW_S - 60L)
        val running = ModelQuota("Fable", 40.0, resetsAt = NOW_S + 3_600L)
        val ridesAccountWeek = ModelQuota("Opus", 70.0)
        val snapshot = reading(NOW_MS - 60_000L, listOf(rolled, running, ridesAccountWeek))

        assertEquals(listOf(running, ridesAccountWeek), snapshot.modelsRunningAt(NOW_S))
        assertEquals(true, snapshot.sevenDay?.resetsAt!! > NOW_S, "the account's own week is still running")
    }
}
