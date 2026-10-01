// NEW: V4-162 — a running head's catalog answers every window from the declaration in force NOW.
//
// WHAT IT PINS: the window METHODS (contextWindowFor, clientLaunchWindow, clientContextWindowFor,
// usageScale) all follow LiveWindows, which is what lets a context_window edit reach a running
// daemon; live() hands a reader of the window FIELDS the same catalog; withWindowsOf changes the
// numbers and nothing else. NEVER-BELOW-STATUS-QUO: a live source with nothing newer than boot, and
// a catalog with no live source at all, answer exactly the windows the catalog was built with.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class LiveWindowsCatalogTest {

    private val rates = ModelRates(input = 0.1, cacheRead = 0.01, output = 0.4)

    private val boot = ModelCatalog(
        discoveryPrefix = "claude-bonsai--",
        models = listOf(
            ModelEntry(id = "bonsai-27b", label = "Bonsai 27B", contextWindow = 131_072, rates = rates),
            ModelEntry(id = "bonsai-9b", label = "Bonsai 9B", contextWindow = 65_536),
        ),
        extraWindows = listOf(ExtraWindow("bonsai-aux", 32_768)),
        windowRules = listOf(WindowRule("bonsai-", 16_384)),
        defaultContextWindow = 131_072,
        pinnedModel = "bonsai-27b",
    )

    /** splice.toml after the edit: the pinned row widened, the extra window, the rule and the default
     *  moved, and a label changed, which is a roster edit and must NOT reach the running head. */
    private val declared = boot.copy(
        models = listOf(
            ModelEntry(id = "bonsai-27b", label = "renamed in the file", contextWindow = 245_760),
            ModelEntry(id = "bonsai-9b", label = "Bonsai 9B", contextWindow = 65_536),
        ),
        extraWindows = listOf(ExtraWindow("bonsai-aux", 49_152)),
        windowRules = listOf(WindowRule("bonsai-", 24_576)),
        defaultContextWindow = 245_760,
    )

    private fun running(current: ModelCatalog?): ModelCatalog = boot.copy(liveWindows = LiveWindows { current })

    @Test
    fun `every window method answers from the declaration in force now`() {
        val head = running(boot.withWindowsOf(declared))

        assertEquals(245_760, head.contextWindowFor("bonsai-27b"))
        assertEquals(245_760, head.contextWindowFor("claude-bonsai--bonsai-27b"))
        assertEquals(49_152, head.contextWindowFor("bonsai-aux"))
        assertEquals(24_576, head.contextWindowFor("bonsai-unlisted"))
        assertEquals(245_760, head.contextWindowFor("elsewhere"))
        assertEquals(245_760, head.clientLaunchWindow)
        assertEquals(245_760, head.clientContextWindowFor("bonsai-27b"))
        // A session launched BEFORE the edit keeps dividing by 131,072: its counts are scaled so it
        // compacts at the new 245,760, and neither the daemon nor the session restarts.
        assertEquals(131_072.0 / 245_760, head.usageScale("bonsai-27b", sessionWindow = 131_072))
        // A session launched after the edit is planted with the new window and rides raw.
        assertEquals(1.0, head.usageScale("bonsai-27b", sessionWindow = 245_760))
    }

    @Test
    fun `the boot windows stand while nothing newer is declared`() {
        for (head in listOf(running(null), boot)) {
            assertEquals(131_072, head.contextWindowFor("bonsai-27b"))
            assertEquals(32_768, head.contextWindowFor("bonsai-aux"))
            assertEquals(16_384, head.contextWindowFor("bonsai-unlisted"))
            assertEquals(131_072, head.clientLaunchWindow)
            assertEquals(1.0, head.usageScale("bonsai-27b", sessionWindow = 131_072))
            assertSame(head, head.live())
        }
    }

    @Test
    fun `withWindowsOf changes the numbers and nothing else`() {
        val merged = boot.withWindowsOf(declared)

        assertEquals(listOf("Bonsai 27B", "Bonsai 9B"), merged.models.map { it.label })
        assertEquals(listOf(rates, null), merged.models.map { it.rates })
        assertEquals(listOf(245_760L, 65_536L), merged.models.map { it.contextWindow })
        assertEquals(declared.extraWindows, merged.extraWindows)
        assertEquals(declared.windowRules, merged.windowRules)
        assertEquals(245_760, merged.defaultContextWindow)
        assertEquals(boot.pinnedModel, merged.pinnedModel)
        assertEquals(boot.discoveryPrefix, merged.discoveryPrefix)
        assertNull(merged.liveWindows)
    }

    @Test
    fun `a row the file no longer lists keeps its own window`() {
        val merged = boot.withWindowsOf(declared.copy(models = declared.models.take(1)))

        assertEquals(listOf("bonsai-27b", "bonsai-9b"), merged.models.map { it.id })
        assertEquals(listOf(245_760L, 65_536L), merged.models.map { it.contextWindow })
    }

    @Test
    fun `serve ceiling stays separate from the target and follows live explicit edits`() {
        val published = boot.copy(
            models = listOf(ModelEntry("bonsai-27b", contextWindow = 400_000, maxContextWindow = 872_000)),
        )
        val edited = published.copy(extraWindows = listOf(ExtraWindow("bonsai-27b", 300_000, 800_000)))
        val live = published.copy(liveWindows = LiveWindows { published.withWindowsOf(edited) })
        assertEquals(300_000L, live.contextWindowFor("bonsai-27b"))
        assertEquals(800_000L, ModelServeWindows.forRow(live, "claude-bonsai--bonsai-27b[500k]"))
        val withoutOverride = published.withWindowsOf(
            published.copy(models = listOf(ModelEntry("bonsai-27b", contextWindow = 300_000))),
        )
        assertEquals(872_000L, ModelServeWindows.forRow(withoutOverride, "bonsai-27b"))
        assertEquals(131_072.0 / 300_000, withoutOverride.usageScale("bonsai-27b", 131_072))
    }

    @Test
    fun `a row without a ceiling retains its target and suffixed rows retain their own override`() {
        val catalog = boot.copy(
            models = listOf(ModelEntry("bonsai-27b", contextWindow = 400_000)),
            extraWindows = listOf(
                ExtraWindow("bonsai-27b", 400_000, 872_000),
                ExtraWindow("bonsai-27b[500k]", 300_000, 500_000),
            ),
        )
        assertEquals(872_000L, ModelServeWindows.forRow(catalog, "bonsai-27b"))
        assertEquals(500_000L, ModelServeWindows.forRow(catalog, "claude-bonsai--bonsai-27b[500k]"))
        assertEquals(872_000L, ModelServeWindows.forRow(catalog, "bonsai-27b[1m]"))
        assertEquals(32_768L, ModelServeWindows.forRow(boot, "bonsai-aux"))
        assertEquals(16_384L, ModelServeWindows.forRow(boot, "bonsai-unknown"))
    }

    @Test
    fun `a bare id served only by numeric tiers takes the same last row for target and ceiling`() {
        val catalog = boot.copy(
            models = listOf(
                ModelEntry("bonsai-27b[500k]", contextWindow = 400_000),
                ModelEntry("bonsai-27b[1m]", contextWindow = 600_000),
            ),
            extraWindows = listOf(
                ExtraWindow("bonsai-27b[500k]", 400_000, 500_000),
                ExtraWindow("bonsai-27b[1m]", 600_000, 872_000),
            ),
        )
        assertEquals(600_000L, catalog.contextWindowFor("bonsai-27b"))
        assertEquals(872_000L, ModelServeWindows.forRow(catalog, "bonsai-27b"))
        val windows = catalog.extraWindows.map {
            if (it.id.endsWith("[1m]")) it.copy(maxContextWindow = null) else it
        }
        val noCeiling = catalog.copy(extraWindows = windows)
        assertEquals(600_000L, ModelServeWindows.forRow(noCeiling, "bonsai-27b"))
        val bare = catalog.copy(models = catalog.models + ModelEntry("bonsai-27b", contextWindow = 300_000))
        assertEquals(300_000L, ModelServeWindows.forRow(bare, "bonsai-27b"))
    }

    @Test
    fun `live hands a field reader the catalog in force`() {
        val merged = boot.withWindowsOf(declared)

        assertSame(merged, running(merged).live())
    }
}
