// NEW: V4-232's window arithmetic for a row the client resolves as a Claude model it knows (client_model).
// The client then sizes the row from its own table (200k behind any head's base URL), never from the
// launch env, so usage scaling is what keeps the row compacting at the window the runtime serves.
package campaign.v4232

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry

class PresentedRowWindowTest {
    private val bonsai =
        ModelEntry("bonsai-2-27b", "Bonsai", contextWindow = 245_760L, clientModel = "claude-sonnet-4-6")

    private fun catalog(vararg rows: ModelEntry, pinned: String = rows.first().id) = ModelCatalog(
        discoveryPrefix = "claude-bonsai--",
        models = rows.toList(),
        defaultContextWindow = 128_000L,
        pinnedModel = pinned,
    )

    @Test
    fun `a presented row is windowed by the client's table and carried to its own window by scaling`() {
        val cat = catalog(bonsai)

        assertEquals(200_000L, cat.clientContextWindowFor("bonsai-2-27b"))
        assertEquals(200_000.0 / 245_760, cat.usageScale("bonsai-2-27b"), 1e-12)
        assertFalse(cat.envGoverned("bonsai-2-27b"), "a status-line post reveals the client's table here, not the env")
        assertEquals(
            200_000L,
            cat.clientContextWindowFor("bonsai-2-27b", sessionWindow = 300_000L),
            "a session window learned from an env row does not move a presented one",
        )
    }

    @Test
    fun `an unpresented row beside it keeps the env the launch planted`() {
        val qwen = ModelEntry("qwen-local", "Qwen", contextWindow = 131_072L)
        val cat = catalog(bonsai, qwen, pinned = "qwen-local")

        assertTrue(cat.envGoverned("qwen-local"))
        assertEquals(cat.clientLaunchWindow, cat.clientContextWindowFor("qwen-local"))
        assertEquals(1.0, cat.usageScale("qwen-local"))
    }

    @Test
    fun `two rows over one upstream id keep their own answers, and an undeclared tier takes its upstream row's`() {
        val plain = ModelEntry("bonsai-2-27b[500k]", "Bonsai 500k", contextWindow = 500_000L)
        val cat = catalog(bonsai, plain)

        assertTrue(cat.presented.covers("bonsai-2-27b"))
        assertTrue(cat.presented.covers("claude-bonsai--bonsai-2-27b"), "the discovery-wrapped spelling")
        assertFalse(cat.presented.covers("bonsai-2-27b[500k]"), "its own raw row presents nothing")
        assertTrue(cat.presented.covers("bonsai-2-27b[1m]"), "undeclared: the upstream id's own row answers")
    }

    @Test
    fun `the overrides map each presented Claude model to its row, and a head presenting none has none`() {
        assertEquals(mapOf("claude-sonnet-4-6" to "bonsai-2-27b"), catalog(bonsai).presented.overrides)

        val plain = catalog(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272_000L))
        assertEquals(emptyMap<String, String>(), plain.presented.overrides)
        assertFalse(plain.presented.covers("gpt-5.6-sol"))
    }

    @Test
    fun `a client_model the client cannot know, or one named twice, is refused at load`() {
        val unknown = assertThrows<IllegalArgumentException> { catalog(bonsai.copy(clientModel = "bonsai-large")) }
        assertTrue("claude-" in unknown.message.orEmpty(), unknown.message)

        val qwen = ModelEntry("qwen-local", "Qwen", contextWindow = 131_072L, clientModel = "claude-sonnet-4-6")
        val twice = assertThrows<IllegalArgumentException> { catalog(bonsai, qwen) }
        assertTrue("claude-sonnet-4-6" in twice.message.orEmpty(), twice.message)
    }
}
