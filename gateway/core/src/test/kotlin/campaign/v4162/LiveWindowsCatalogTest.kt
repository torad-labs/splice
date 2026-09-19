// NEW: V4-162 — a running head's catalog answers every window from the declaration in force NOW.
//
// WHAT IT PINS: the window METHODS (contextWindowFor, clientLaunchWindow, clientContextWindowFor,
// usageScale) all follow LiveWindows, which is what lets a context_window edit reach a running
// daemon; live() hands a reader of the window FIELDS the same catalog; withWindowsOf changes the
// numbers and nothing else. NEVER-BELOW-STATUS-QUO: a live source with nothing newer than boot, and
// a catalog with no live source at all, answer exactly the windows the catalog was built with.
package campaign.v4162

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import splice.core.model.ExtraWindow
import splice.core.model.LiveWindows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.core.model.WindowRule

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
    fun `live hands a field reader the catalog in force`() {
        val merged = boot.withWindowsOf(declared)

        assertSame(merged, running(merged).live())
    }
}
