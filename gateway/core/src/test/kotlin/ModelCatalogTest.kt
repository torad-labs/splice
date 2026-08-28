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

    @Test
    fun `two tier rows on ONE upstream model keep their own windows`() {
        // xAI ships ONE id per model (grok-4.6 = 500k); unlike Moonshot it has no `-256k` sibling to
        // pin a smaller window against. So offering "capped by default, long context on request"
        // means TWO picker rows that strip to the SAME upstream id — which the old shape could not
        // express: the suffix regex matched only the literal "[1m]", and exactWindows keyed the
        // STRIPPED id, so the second row either collided on the window or shipped an invalid id.
        val xai = ModelCatalog(
            discoveryPrefix = "claude-grok--",
            models = listOf(
                ModelEntry(id = "grok-4.6", label = "Grok 4.6", contextWindow = 256_000),
                ModelEntry(id = "grok-4.6[500k]", label = "Grok 4.6 (500k)", contextWindow = 500_000),
            ),
            defaultContextWindow = 256_000,
        )
        assertEquals("grok-4.6", xai.stripSuffixes("grok-4.6[500k]"), "any bracket tier strips, not just [1m]")
        assertEquals(256_000L, xai.contextWindowFor("grok-4.6"), "the capped row keeps the deliberate 256k")
        assertEquals(500_000L, xai.contextWindowFor("grok-4.6[500k]"), "the long-context row gets its own window")
        assertEquals(500_000L, xai.contextWindowFor("claude-grok--grok-4.6[500k]"), "wrapped form too")
        assertTrue(xai.contains("grok-4.6[500k]"), "the tier row is still owned by this head")
        assertTrue(xai.contains("grok-4.6"), "and so is the bare upstream id")
    }

    @Test
    fun `usage scaling gives a row a window the client cannot be told about`() {
        // Claude Code resolves a window two ways only: /\[1m\]/i on the id -> 1e6, else the ONE
        // process-wide CLAUDE_CODE_MAX_CONTEXT_TOKENS (= the pinned row's window). A third window
        // therefore cannot come from the client — it comes from us scaling the token counts we
        // report, since it compacts on (input+cache)/window and splice owns the numerator.
        val xai = ModelCatalog(
            discoveryPrefix = "claude-grok--",
            models = listOf(
                ModelEntry(id = "grok-4.6", contextWindow = 256_000),
                ModelEntry(id = "grok-4.6[500k]", contextWindow = 500_000),
                ModelEntry(id = "grok-4.3[1m]", contextWindow = 1_000_000),
            ),
            defaultContextWindow = 256_000,
            pinnedModel = "grok-4.6",
        )
        // the pinned row IS the env: exact counts, nothing to correct
        assertEquals(256_000L, xai.clientContextWindowFor("grok-4.6"))
        assertEquals(1.0, xai.usageScale("grok-4.6"))
        // a "[1m]" row bypasses the env entirely, so it is honest too
        assertEquals(1_000_000L, xai.clientContextWindowFor("grok-4.3[1m]"))
        assertEquals(1.0, xai.usageScale("grok-4.3[1m]"))
        // the middle row is the one the client has no way to represent: it believes 256k, so
        // halving the reported counts makes it compact when REAL usage reaches 500k
        assertEquals(256_000L, xai.clientContextWindowFor("grok-4.6[500k]"))
        assertEquals(0.512, xai.usageScale("grok-4.6[500k]"))
        assertEquals(0.512, xai.usageScale("claude-grok--grok-4.6[500k]"), "wrapped form too")
    }

    @Test
    fun `a 1m row is never scaled - the id already speaks the client's language`() {
        // kimi ships k3[1m] at 1048576 while Claude Code returns a flat 1e6 for any "[1m]" id.
        // That 4.6% gap is notation, not intent, and must not become a scale factor.
        val kimi = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(
                ModelEntry(id = "k3-256k", contextWindow = 262_144),
                ModelEntry(id = "k3[1m]", contextWindow = 1_048_576),
            ),
            defaultContextWindow = 262_144,
            pinnedModel = "k3-256k",
        )
        assertEquals(1.0, kimi.usageScale("k3[1m]"))
        assertEquals(1.0, kimi.usageScale("k3-256k"), "the pinned row is the env: exact")
    }

    @Test
    fun `a row declaring LESS than the session window compacts at its own window`() {
        // codex pins a 400k model but ships a 128k spark row; the client gives every non-[1m] id the
        // one env window, so spark would run to 400k and overrun upstream. Scaling up fixes that.
        val codex = ModelCatalog(
            discoveryPrefix = "claude-codex--",
            models = listOf(
                ModelEntry(id = "gpt-5.6-sol", contextWindow = 400_000),
                ModelEntry(id = "gpt-5.3-codex-spark", contextWindow = 128_000),
            ),
            defaultContextWindow = 400_000,
            pinnedModel = "gpt-5.6-sol",
        )
        assertEquals(1.0, codex.usageScale("gpt-5.6-sol"))
        assertEquals(3.125, codex.usageScale("gpt-5.3-codex-spark"))
    }

    @Test
    fun `contextWindowFor strips 1m suffix so picker id windows resolve without extraWindows`() {
        // Residual of the membership fix: modelIds stripped but exactWindows keyed raw picker ids,
        // so contains("k3[1m]") passed while contextWindowFor fell to default 256k.
        val kimi = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(
                ModelEntry(id = "k3[1m]", label = "Kimi K3 (1M)", contextWindow = 1_048_576),
            ),
            defaultContextWindow = 262_144,
        )
        assertEquals(1_048_576, kimi.contextWindowFor("k3[1m]"), "picker id")
        assertEquals(1_048_576, kimi.contextWindowFor("k3"), "bare upstream after strip")
        assertEquals(1_048_576, kimi.contextWindowFor("claude-kimi--k3[1m]"), "wrapped picker id")
        assertEquals(1_048_576, kimi.contextWindowFor("claude-kimi--k3"), "wrapped bare id")
    }
}
