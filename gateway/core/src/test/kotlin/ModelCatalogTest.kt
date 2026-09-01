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
        assertEquals(
            "provider-model[preview]",
            catalog.stripSuffixes("provider-model[preview]"),
            "a genuine bracketed provider id is not a numeric tier hint",
        )
        // INVERTED (DR-27, 2026-08-30): this arm pinned 272_000 under the claim "the client
        // recognizes [1m] only as a trailing suffix" — read against the actual cli 2.1.233 binary
        // that claim is false: `function KE(e){...return/\[1m\]/i.test(e)}` is an UNANCHORED
        // containsMatch and G4u gates window resolution on KE first. The predicate exists to
        // predict the client, so it mirrors the client.
        assertEquals(
            1_000_000L,
            catalog.clientContextWindowFor("gpt-5.6-sol[1m]-preview"),
            "the client's KE() is containsMatch — [1m] anywhere in the id means 1e6",
        )
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
                ModelEntry(id = "k3[1m]", label = "Kimi K3 (1M)", contextWindow = 1_000_000),
                ModelEntry(id = "kimi-for-coding", contextWindow = 262_144),
            ),
            extraWindows = listOf(ExtraWindow(id = "k3", contextWindow = 1_000_000)),
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
        assertEquals("grok-4.6", xai.stripSuffixes("grok-4.6[500k]"), "any numeric bracket tier strips, not just [1m]")
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

    // DR-24: the 0.512 pin above once depended on exactWindows last-wins — fixture DECLARATION
    // ORDER — so reordering the two rows silently flipped the expected scale. rawWindows raw-first
    // made both lookups order-independent; this arm REORDERS the same fixture and demands the same
    // numbers, so a regression back to stripped-key collision fails HERE instead of moving a green
    // number somewhere else.
    @Test
    fun `the 500k scale is declaration-order-proof`() {
        val orders = listOf(
            listOf(
                ModelEntry(id = "grok-4.6", contextWindow = 256_000),
                ModelEntry(id = "grok-4.6[500k]", contextWindow = 500_000),
            ),
            listOf(
                ModelEntry(id = "grok-4.6[500k]", contextWindow = 500_000),
                ModelEntry(id = "grok-4.6", contextWindow = 256_000),
            ),
        )
        for (rows in orders) {
            val xai = ModelCatalog(
                discoveryPrefix = "claude-grok--",
                models = rows,
                defaultContextWindow = 256_000,
                pinnedModel = "grok-4.6",
            )
            assertEquals(0.512, xai.usageScale("grok-4.6[500k]"), "order ${rows.map { it.id }}")
            assertEquals(1.0, xai.usageScale("grok-4.6"), "order ${rows.map { it.id }}")
        }
    }

    // DR-27: suffix stripping is a NUMERIC-TIER-only, END-anchored rule. A genuine vendor id with
    // a bracket (model[preview]) must reach the provider byte-for-byte, and a mid-string "[1m]"
    // still gets Claude Code's 1e6 — the client's own detection is containsMatch, not anchored,
    // and we mirror the client rather than improve on it.
    @Test
    fun `non-tier brackets ship byte-identical and only trailing tier hints strip`() {
        val cat = ModelCatalog(
            discoveryPrefix = "claude-x--",
            models = listOf(
                ModelEntry(id = "model[preview]", contextWindow = 128_000),
                ModelEntry(id = "k3[1m]", contextWindow = 1_000_000),
                ModelEntry(id = "k3[1m]-preview", contextWindow = 1_000_000),
            ),
            defaultContextWindow = 128_000,
            pinnedModel = "model[preview]",
        )
        assertEquals("model[preview]", cat.stripSuffixes("model[preview]"), "non-tier bracket survives")
        assertEquals("k3", cat.stripSuffixes("k3[1m]"))
        assertEquals("k3[1m]-preview", cat.stripSuffixes("k3[1m]-preview"), "anchored: mid-string stays")
        assertEquals(128_000L, cat.contextWindowFor("model[preview]"))
        assertEquals(1_000_000L, cat.clientContextWindowFor("k3[1m]-preview"), "client containsMatch mirrored")
        assertTrue(cat.contains("model[preview]"))
    }

    @Test
    fun `a DECLARED 1m row needs no exemption - the arithmetic already yields exactly 1`() {
        val kimi = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(
                ModelEntry(id = "k3-256k", contextWindow = 262_144),
                ModelEntry(id = "k3[1m]", contextWindow = 1_000_000),
            ),
            defaultContextWindow = 262_144,
            pinnedModel = "k3-256k",
        )
        assertEquals(1.0, kimi.usageScale("k3[1m]"), "client 1e6 over declared 1e6")
        assertEquals(1.0, kimi.usageScale("k3-256k"), "the pinned row is the env: exact")
    }

    @Test
    fun `a 1m row declaring 1024x1024 instead of 1e6 DOES scale - the config must say 1000000`() {
        // Review finding: config/splice.example.toml shipped k3[1m] at 1048576 AND pinned the kimi
        // head to that row, so every default turn scaled 0.9537 and compacted ~4.6% late with
        // nothing logging the factor. The first version of the test above hid this by declaring
        // 1_000_000 in the fixture while the shipped TOML said 1_048_576 — the fixture was fitted to
        // the assertion. This pins the real behaviour instead: Claude Code hardcodes 1e6 for a "[1m]"
        // id and never reads our number, so any other declared value IS a scale factor, by design.
        // The fix belongs in the config, and this test is what makes choosing wrong visible.
        val declaring1024 = ModelCatalog(
            discoveryPrefix = "claude-kimi--",
            models = listOf(ModelEntry(id = "k3[1m]", contextWindow = 1_048_576)),
            defaultContextWindow = 262_144,
            pinnedModel = "k3[1m]",
        )
        assertEquals(1_000_000L, declaring1024.clientContextWindowFor("k3[1m]"), "client ignores our number")
        assertEquals(1_000_000.0 / 1_048_576.0, declaring1024.usageScale("k3[1m]"), "not 1.0 — this is the bug")
    }

    @Test
    fun `an UNDECLARED 1m id is scaled to the real ceiling, not trusted`() {
        // contains() strips the suffix before its membership test, so "grok-4.6[1m]" — a row in no
        // catalog — passes the head's own-models gate, and Claude Code applies its /\[1m\]/i rule to
        // the raw string and uses 1e6 regardless of anything splice does. Left unscaled the session
        // runs toward 850k on a model xAI cuts off at 500k: one hard upstream failure, no warning.
        // Scaling maps the client's 1e6 onto the stripped id's OWN row — the bare 256k cap here —
        // never onto whichever colliding sibling was declared last: this arm's old 500k expectation
        // was exactWindows' associate-last-wins leaking through an undeclared id (DR-24 redo), so
        // the same fixture reversed moved the denominator. Both orders must agree on the bare row.
        val rows = listOf(
            ModelEntry(id = "grok-4.6", contextWindow = 256_000),
            ModelEntry(id = "grok-4.6[500k]", contextWindow = 500_000),
        )
        for (models in listOf(rows, rows.reversed())) {
            val order = "order ${models.map { it.id }}"
            val xai = ModelCatalog(
                discoveryPrefix = "claude-grok--",
                models = models,
                defaultContextWindow = 256_000,
                pinnedModel = "grok-4.6",
            )
            assertTrue(xai.contains("grok-4.6[1m]"), "the gate lets it through — that is the hazard ($order)")
            assertEquals(
                256_000L,
                xai.contextWindowFor("grok-4.6[1m]"),
                "the undeclared tier's denominator is the bare row's own window ($order)",
            )
            assertEquals(3.90625, xai.usageScale("grok-4.6[1m]"), "1e6 client / 256k real ($order)")
            assertEquals(1_000_000L, xai.clientContextWindowFor("grok-4.6[1m]"), "client's number regardless ($order)")
        }
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
                ModelEntry(id = "k3[1m]", label = "Kimi K3 (1M)", contextWindow = 1_000_000),
            ),
            defaultContextWindow = 262_144,
        )
        assertEquals(1_000_000, kimi.contextWindowFor("k3[1m]"), "picker id")
        assertEquals(1_000_000, kimi.contextWindowFor("k3"), "bare upstream after strip")
        assertEquals(1_000_000, kimi.contextWindowFor("claude-kimi--k3[1m]"), "wrapped picker id")
        assertEquals(1_000_000, kimi.contextWindowFor("claude-kimi--k3"), "wrapped bare id")
    }
}
