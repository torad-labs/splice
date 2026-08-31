import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.config.ConfigService
import splice.core.config.StatePaths
import splice.core.model.ModelEntry
import splice.core.topology.AuthConfig
import splice.core.topology.DaemonConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.HeadModel
import splice.core.topology.ProviderConfig
import splice.core.topology.QuirksConfig
import splice.core.topology.ToolSurfaceConfig
import splice.core.topology.Topology
import splice.core.topology.TopologyKnobLayer
import java.nio.file.Files

class TopologyConfigOverridesTest {

    private val topology = Topology(
        daemon = DaemonConfig(controlPort = 4123),
        providers = mapOf(
            "codex" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://toml.example/codex",
                auth = AuthConfig("chatgpt-oauth", file = "~/custom/codex.json"),
                models = listOf(ModelEntry("toml-codex", contextWindow = 100_000)),
            ),
            "grok" to ProviderConfig(
                dialect = Dialect.OPENAI_RESPONSES,
                baseUrl = "https://toml.example/grok",
                auth = AuthConfig("grok-oauth", file = "~/custom/grok.json"),
                models = listOf(ModelEntry("toml-grok", contextWindow = 200_000)),
            ),
        ),
        heads = mapOf(
            "codex" to HeadConfig("codex", 4101, "claude-codex--", "toml-codex"),
            "grok" to HeadConfig("grok", 4102, "claude-grok--", "toml-grok"),
        ),
    )

    @Test
    fun `topology seeds every legacy management knob it owns`() {
        val layer = TopologyKnobLayer(topology).configOverrides()
        assertEquals("4123", layer["controlPort"])
        assertEquals("4101", layer["port"])
        assertEquals("toml-codex", layer["pinnedModel"])
        assertEquals("https://toml.example/codex", layer["chatgptApiBase"])
        assertEquals("~/custom/codex.json", layer["codexAuthPath"])
        assertEquals("4102", layer["grokPort"])
        assertEquals("toml-grok", layer["grokModel"])
        assertEquals("https://toml.example/grok", layer["xaiApiBase"])
        assertEquals("~/custom/grok.json", layer["grokAuthPath"])
    }

    @Test
    fun `head key text cannot seed Grok management knobs`() {
        val unrelated = Topology(
            providers = mapOf(
                "openrouter" to ProviderConfig(
                    dialect = Dialect.OPENAI_RESPONSES,
                    baseUrl = "https://openrouter.example",
                    auth = AuthConfig("api-key"),
                ),
            ),
            heads = mapOf(
                "not-grok" to HeadConfig("openrouter", 4107, "claude-router--", "router-model"),
            ),
        )
        val layer = TopologyKnobLayer(unrelated).configOverrides()

        assertNull(layer["grokPort"])
        assertNull(layer["grokModel"])
        assertNull(layer["xaiApiBase"])
    }

    @Test
    fun `environment wins over topology for restart-applied settings`() {
        val paths = StatePaths(baseOverride = Files.createTempDirectory("topology-config"))
        val service = ConfigService(
            paths,
            headOverrides = TopologyKnobLayer(topology).configOverrides(),
            envReader = { name ->
                when (name) {
                    "SPLICE_CONTROL_PORT" -> "5123"
                    "CLAUDEX_PINNED_MODEL" -> "env-codex"
                    else -> null
                }
            },
        )
        assertEquals(5123, service.getConfig().controlPort)
        assertEquals("env-codex", service.getConfig().pinnedModel)
    }

    @Test
    fun `context override changes exact models and fallback consistently`() {
        val provider = topology.providers.getValue("codex")
        val head = topology.heads.getValue("codex")
        val catalog = provider.catalogFor(head, contextWindowOverride = 333_000)
        assertEquals(333_000, catalog.contextWindowFor("toml-codex"))
        assertEquals(333_000, catalog.contextWindowFor("future-model"))
    }

    @Test
    fun `head owns its ordered model roster and one honest process window`() {
        val provider = topology.providers.getValue("codex").copy(
            models = listOf(
                ModelEntry("narrow", contextWindow = 200_000),
                ModelEntry("wide-a", contextWindow = 500_000),
                ModelEntry("wide-b", contextWindow = 500_000),
            ),
        )
        val head = topology.heads.getValue("codex").copy(
            pinnedModel = "wide-b",
            models = listOf(HeadModel("wide-b", "opus"), HeadModel("wide-a", "sonnet")),
            contextWindow = 500_000,
        )

        val catalog = provider.catalogFor(head)

        assertEquals(listOf("wide-b", "wide-a"), catalog.availableModelIds())
        assertEquals(provider.models.map { it.id }, provider.catalogFor(head.copy(models = null)).availableModelIds())
        assertEquals(setOf(500_000L), catalog.models.map { it.contextWindow }.toSet())
        assertEquals(1.0, catalog.usageScale("wide-a"))
        assertThrows(IllegalArgumentException::class.java) {
            provider.catalogFor(head.copy(models = listOf(HeadModel("not-declared-by-provider"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.catalogFor(head.copy(models = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.catalogFor(
                head.copy(models = listOf(HeadModel("wide-b", "opus"), HeadModel("wide-a", "OPUS"))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            provider.catalogFor(head.copy(models = listOf(HeadModel("wide-b", "turbo"))))
        }
        // DR-44d: duplicate model IDS reject (the arm above pins duplicate SLOTS; this claim was
        // enforced but unpinned — a stale roster line repeating an id must fail loud, not last-wins).
        assertThrows(IllegalArgumentException::class.java) {
            provider.catalogFor(
                head.copy(models = listOf(HeadModel("wide-b", "opus"), HeadModel("wide-b", "sonnet"))),
            )
        }
    }

    // DR-44a: the pinned-membership failure names the id, the roster, and the knob provenance —
    // resolveHeadConfig swaps pinned_model with the pinnedModel/grokModel knob for oauth heads, so
    // the failing id can come from env/config.json/PATCH and appear NOWHERE in splice.toml. An
    // operator grepping the TOML for a bare "pinned model must belong" message found nothing.
    @Test
    fun `pinned-membership failure names the id, the roster, and the knob provenance`() {
        val provider = topology.providers.getValue("codex").copy(
            models = listOf(
                ModelEntry("narrow", contextWindow = 200_000),
                ModelEntry("wide-a", contextWindow = 500_000),
            ),
        )
        val head = topology.heads.getValue("codex").copy(
            pinnedModel = "env-seeded-ghost",
            models = listOf(HeadModel("wide-a", "opus"), HeadModel("narrow", "sonnet")),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) { provider.catalogFor(head) }

        val message = failure.message.orEmpty()
        assertTrue(message.contains("'env-seeded-ghost'"), message)
        assertTrue(message.contains("wide-a, narrow"), message)
        assertTrue(message.contains("pinnedModel/grokModel knob"), message)
    }

    // [providers.*.quirks.tool_surface] — the nullable-overlay idiom (Topology.kt): an absent
    // table parses to null, and a present table carries every field through untouched. The
    // TOML->ToolDeferralPolicy mapping itself (toolDeferralPolicy, incl. the enabled=false and
    // daemon-wide-off cases) lives in :app's Daemon.kt and cannot be reached from :core — this
    // pins the shape :app's mapper reads.
    @Test
    fun `tool_surface quirks table - absent parses null, present carries every field`() {
        val absent = topology.providers.getValue("codex").quirks.toolSurface
        assertNull(absent)

        val withTable = topology.providers.getValue("codex").copy(
            quirks = QuirksConfig(
                toolSurface = ToolSurfaceConfig(
                    enabled = true,
                    deferPrefixes = listOf("mcp__"),
                    defer = listOf("Task"),
                    eager = listOf("mcp__exa__web_search_exa"),
                    minDeferred = 6,
                    searchLimit = 5,
                    searchRounds = 2,
                ),
            ),
        )
        val table = withTable.quirks.toolSurface!!
        assertEquals(true, table.enabled)
        assertEquals(listOf("mcp__"), table.deferPrefixes)
        assertEquals(listOf("Task"), table.defer)
        assertEquals(listOf("mcp__exa__web_search_exa"), table.eager)
        assertEquals(6, table.minDeferred)
        assertEquals(5, table.searchLimit)
        assertEquals(2, table.searchRounds)
    }

    // ktoml hands back a QUOTED TOML key with its quote characters intact, and
    // `extra_headers = { "anthropic-version" = "..." }` is both valid TOML and the natural thing
    // to write for a dashed header name. Unnormalized, that ships a header literally named
    // "anthropic-version" — quotes included — to the upstream. Both spellings must agree.
    @Test
    fun `staticHeaders strips TOML key quoting so both spellings agree`() {
        val quoted = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.anthropic.com",
            auth = AuthConfig("client"),
            extraHeaders = mapOf("\"anthropic-version\"" to "2023-06-01"),
        )
        val bare = quoted.copy(extraHeaders = mapOf("anthropic-version" to "2023-06-01"))
        assertEquals(mapOf("anthropic-version" to "2023-06-01"), quoted.staticHeaders)
        assertEquals(quoted.staticHeaders, bare.staticHeaders)
    }

    @Test
    fun `client auth rejects configured upstream credentials case-insensitively`() {
        listOf("\"aUtHoRiZaTiOn\"", "\"X-Api-Key\"").forEach { header ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderConfig(
                    dialect = Dialect.ANTHROPIC_PASSTHROUGH,
                    baseUrl = "https://api.anthropic.com",
                    auth = AuthConfig("client"),
                    extraHeaders = mapOf(header to "splice-held-secret"),
                )
            }
        }
    }

    @Test
    fun `non-client auth may configure its own upstream credential headers`() {
        val provider = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.anthropic.com",
            auth = AuthConfig("api-key"),
            extraHeaders = mapOf("\"Authorization\"" to "Bearer configured", "X-API-KEY" to "configured"),
        )

        assertEquals(
            mapOf("Authorization" to "Bearer configured", "X-API-KEY" to "configured"),
            provider.staticHeaders,
        )
    }

    // ABSENT means "keep the head's base profile", never "false" — the nullable-overlay idiom.
    // Non-nullable knobs would make a pre-campaign splice.toml silently neuter a kimi head.
    @Test
    fun `passthrough quirks are absent by default so a base profile survives`() {
        val q = QuirksConfig()
        assertNull(q.mfjs)
        assertNull(q.stripCacheControl)
        assertNull(q.synthesizeSignatures)
        assertNull(q.mapThinkingAdaptive)
        assertNull(q.stripSamplingParams)
        assertNull(q.blockAllowlist)
    }
}
