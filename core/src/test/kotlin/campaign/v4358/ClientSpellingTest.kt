// NEW: V4-358's rule for which rows the client is told are 1M-context, and the window arithmetic that
// makes telling it safe. Claude Code keeps 100 images in a request, 600 for an id it counts as 1M-context;
// a row whose window holds far more than 100 screenshots is handed to the client with the 1M hint, and
// usage scaling carries the client's 1e6 back to the row's real window so it still compacts there.
package campaign.v4358

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.CLAUDE_CODE_ONE_MILLION
import splice.core.model.ClientSpelling
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry

class ClientSpellingTest {
    private fun catalog(vararg rows: ModelEntry, pinned: String = rows.first().id) = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = rows.toList(),
        defaultContextWindow = 272_000L,
        pinnedModel = pinned,
    )

    private val sol = ModelEntry("gpt-6-sol", "Sol", contextWindow = 872_000L)

    @Test
    fun `a row over the floor is handed to the client with the 1M hint, and one under it as it is`() {
        val cat = catalog(
            sol,
            ModelEntry("grok-4.6", "Grok", contextWindow = 500_000L),
            ModelEntry("gpt-5.6-sol", "Sol 5.6", contextWindow = 272_000L),
            ModelEntry("kimi-for-coding", "Kimi", contextWindow = 262_144L),
        )
        val spelling = ClientSpelling(cat)

        assertEquals("gpt-6-sol[1m]", spelling.of("gpt-6-sol"))
        assertEquals("grok-4.6[1m]", spelling.of("grok-4.6"))
        assertEquals("gpt-5.6-sol", spelling.of("gpt-5.6-sol"), "272k: the compaction line comes before 100 images")
        assertEquals("kimi-for-coding", spelling.of("kimi-for-coding"))
    }

    @Test
    fun `the floor is the smallest window holding twice the image cap of screenshots, rounded up`() {
        val patches = 52L * 33L // ceil(1440/28) * ceil(900/28): a 1440x900 screenshot, in the client's 28-pixel patches
        val line = { window: Long -> 0.85 * (window - 20_000L) } // the launch's 85%, less the client's output reserve
        val at = { window: Long -> ClientSpelling(catalog(ModelEntry("m", "M", contextWindow = window))).of("m") }

        assertTrue(line(423_764L) < 2 * 100 * patches, "one token under the derived window holds fewer than 200")
        assertTrue(line(423_765L) >= 2 * 100 * patches, "the derived window holds 200 screenshots")
        assertEquals("m[1m]", at(425_000L))
        assertEquals("m", at(424_999L), "the floor is the derived window rounded up to 425,000")
        assertTrue(line(272_000L) < 2 * 100 * patches, "a 272k row's line comes before 200 screenshots")
    }

    @Test
    fun `an id the client already resolves, or that the operator already spelled, is never renamed`() {
        val cat = catalog(
            ModelEntry("k3[1m]", "K3", contextWindow = 1_000_000L),
            ModelEntry("bonsai-500k[500k]", "Bonsai", contextWindow = 500_000L),
            ModelEntry("claude-opus-4-6", "Opus", contextWindow = 1_000_000L),
            ModelEntry("presented", "Presented", contextWindow = 900_000L, clientModel = "claude-sonnet-4-6"),
        )
        val spelling = ClientSpelling(cat)

        assertEquals("k3[1m]", spelling.of("k3[1m]"), "already carries the hint")
        assertEquals("bonsai-500k[500k]", spelling.of("bonsai-500k[500k]"), "a tier hint the operator wrote")
        assertEquals("claude-opus-4-6", spelling.of("claude-opus-4-6"), "the client's own id has its own rule")
        assertEquals("presented", spelling.of("presented"), "a presented row is resolved as a Claude model")
        assertEquals("not-served", spelling.of("not-served"), "a row this head does not serve is not ours")
    }

    @Test
    fun `the client's 1M window scaled by usage compacts a spelled row at its own real ceiling`() {
        val cat = catalog(sol, pinned = "gpt-6-sol")
        val held = ClientSpelling(cat).of("gpt-6-sol")

        assertEquals(CLAUDE_CODE_ONE_MILLION, cat.clientContextWindowFor(held), "what the client divides by")
        assertEquals(1e6 / 872_000, cat.usageScale(held), 1e-12)
        // The client compacts when what it is told reaches its line: 85% of (its window - 20k). Told
        // real * scale, that is real = line / scale, which must be the line an unspelled row would have hit.
        val clientLine = 0.85 * (CLAUDE_CODE_ONE_MILLION - 20_000L)
        val realAtCompaction = clientLine / cat.usageScale(held)
        val unspelled = 0.85 * (872_000 - 20_000L)
        assertEquals(unspelled, realAtCompaction, unspelled * 0.005, "within the reserve's own 0.3%: it is not scaled")
        assertTrue(realAtCompaction < 872_000, "and never past what the backend serves")
    }

    @Test
    fun `the request's own witness of a 1M window scales a bare id exactly as the spelled one`() {
        val cat = catalog(sol, ModelEntry("gpt-5.6-sol", "Sol 5.6", contextWindow = 272_000L), pinned = "gpt-6-sol")

        // The client strips the hint from the body's model and says it in a beta, so the head sees the bare id.
        assertEquals(1e6 / 872_000, cat.usageScale("gpt-6-sol", sessionWindow = CLAUDE_CODE_ONE_MILLION), 1e-12)
        assertEquals(1e6 / 272_000, cat.usageScale("gpt-5.6-sol", sessionWindow = CLAUDE_CODE_ONE_MILLION), 1e-12)
        assertEquals(1.0, cat.usageScale("gpt-6-sol"), "no witness: the launch env's window, as before")
    }
}
