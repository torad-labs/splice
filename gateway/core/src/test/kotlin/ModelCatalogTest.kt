// PORT-OF: server/test/codex-models.test.mjs @ pre-public-port-baseline semantics — exact-before-prefix-before-
// default resolution order, wrap/unwrap, [1m] suffix strip, discovery rows, allowlist unwrapped.
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.ExtraWindow
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.WindowRule

class ModelCatalogTest {

    private val catalog = ModelCatalog(
        discoveryPrefix = "claude-codex--",
        models = listOf(
            ModelEntry(id = "gpt-5.6-sol", label = "Codex 5.6 Sol", contextWindow = 272_000),
            ModelEntry(id = "gpt-5.3-codex-spark", label = "Codex Spark", contextWindow = 128_000),
        ),
        extraWindows = listOf(
            ExtraWindow(id = "gpt-5.5-1m", contextWindow = 1_000_000),
        ),
        windowRules = listOf(
            WindowRule(prefix = "gpt-5.3-codex-spark", contextWindow = 128_000),
            WindowRule(prefix = "gpt-5.6", contextWindow = 272_000),
            WindowRule(prefix = "gpt-5.3", contextWindow = 272_000),
        ),
        defaultContextWindow = 272_000,
    )

    @Test
    fun `exact match wins before prefix rules`() {
        assertEquals(1_000_000, catalog.contextWindowFor("gpt-5.5-1m"))
        assertEquals(128_000, catalog.contextWindowFor("gpt-5.3-codex-spark"))
    }

    @Test
    fun `prefix rules resolve in declared order - most specific first`() {
        // spark prefix must beat the shorter gpt-5.3 prefix (declared first = wins)
        assertEquals(128_000, catalog.contextWindowFor("gpt-5.3-codex-spark-nightly"))
        assertEquals(272_000, catalog.contextWindowFor("gpt-5.3-mini-unknown"))
        assertEquals(272_000, catalog.contextWindowFor("gpt-5.6-future-variant"))
    }

    @Test
    fun `unknown families fall to the default - never substring-matched`() {
        assertEquals(272_000, catalog.contextWindowFor("o9-preview"))
        // "5.6" appears inside, but startsWith-only means default (the v29 fuzzy bug)
        assertEquals(272_000, catalog.contextWindowFor("experimental-gpt-5.6"))
    }

    @Test
    fun `default override applies only when positive`() {
        assertEquals(500_000, catalog.contextWindowFor("mystery", defaultOverride = 500_000))
        assertEquals(272_000, catalog.contextWindowFor("mystery", defaultOverride = 0))
        assertEquals(272_000, catalog.contextWindowFor(null))
    }

    @Test
    fun `wrap unwrap and suffix strip`() {
        assertEquals("claude-codex--gpt-5.6-sol", catalog.wrap("gpt-5.6-sol"))
        assertEquals("gpt-5.6-sol", catalog.unwrap("claude-codex--gpt-5.6-sol"))
        assertEquals("gpt-5.6-sol", catalog.stripSuffixes("claude-codex--gpt-5.6-sol[1M]"))
        assertEquals("gpt-5.6-sol", catalog.stripSuffixes("gpt-5.6-sol[1m]"))
        assertEquals("plain-id", catalog.unwrap("plain-id"))
    }

    @Test
    fun `discovery rows are wrapped with display names - allowlist stays unwrapped`() {
        val rows = catalog.discoveryRows()
        assertEquals("claude-codex--gpt-5.6-sol", rows.first().id)
        assertEquals("Codex 5.6 Sol", rows.first().displayName)
        assertEquals(listOf("gpt-5.6-sol", "gpt-5.3-codex-spark"), catalog.availableModelIds())
        assertEquals("gpt-5.6-sol", catalog.defaultModel)
    }

    @Test
    fun `catalog membership accepts vendor-qualified ids without name heuristics`() {
        val openRouter = ModelCatalog(
            discoveryPrefix = "claude-openrouter--",
            models = listOf(ModelEntry("anthropic/claude-haiku-4.5", contextWindow = 200_000)),
            defaultContextWindow = 200_000,
        )
        assertTrue(openRouter.contains("anthropic/claude-haiku-4.5"))
        assertTrue(openRouter.contains("claude-openrouter--anthropic/claude-haiku-4.5"))
        assertFalse(openRouter.contains("claude-3-opus"))
    }

    @Test
    fun `contains resolves a 1m-suffixed picker model by every address form`() {
        // kimi k3[1m]: the picker id carries the "[1m]" tier hint; the upstream id is bare "k3".
        // contains strips its query to "k3", so the catalog must recognize "k3" too — a raw modelIds
        // set held "k3[1m]" and 400'd every k3 turn ("proxies its own models only").
        val kimi = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(
                ModelEntry(id = "k3[1m]", label = "Kimi K3 (1M)", contextWindow = 1_048_576),
                ModelEntry(id = "kimi-for-coding", contextWindow = 262_144),
            ),
            extraWindows = listOf(ExtraWindow(id = "k3", contextWindow = 1_048_576)),
            defaultContextWindow = 262_144,
        )
        assertTrue(kimi.contains("k3"), "bare upstream id")
        assertTrue(kimi.contains("k3[1m]"), "picker id with the tier hint")
        assertTrue(kimi.contains("claude-kimi--k3"), "wrapped upstream id")
        assertTrue(kimi.contains("claude-kimi--k3[1m]"), "wrapped picker id")
        assertTrue(kimi.contains("kimi-for-coding"), "a sibling non-suffixed model still resolves")
        assertFalse(kimi.contains("k9"), "a genuinely foreign model is still rejected")
    }
}
