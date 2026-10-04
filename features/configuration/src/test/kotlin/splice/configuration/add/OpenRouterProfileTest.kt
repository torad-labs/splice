// NEW: V4-34 — OpenRouter ships a real model surface. Loading the topology is not enough:
// catalogFor is where modelsFor runs, and that is where duplicate-id and unknown-slot refuse boot.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
import splice.core.model.LongContextRates
import splice.core.model.ModelEntry
import splice.core.model.ModelRates
import splice.topology.TopologyLoader
import java.nio.file.Files
import java.nio.file.Path

class OpenRouterProfileTest {

    private val profile = requireNotNull(AddProfiles().find("openrouter")) { "openrouter profile missing" }

    @Test
    fun `the emitted topology actually loads`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "openrouter head absent after load" }
        assertEquals("claude-openrouter", head.claude.command)
        val provider = requireNotNull(topology.providers["openrouter"]) { "openrouter provider absent" }
        assertEquals(PROFILE_MODELS, provider.models.size, "every declared row must parse")
        val catalog = provider.catalogFor(head)
        assertEquals(PROFILE_MODELS, catalog.models.size, "declared rows are not a serving allowlist")
        assertEquals("anthropic/claude-sonnet-5", catalog.pinnedModel)
    }

    @Test
    fun `fresh OpenRouter heads expose discovered priced rows and retain declared windows`() {
        val topology = TopologyLoader.parse(DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        val head = topology.heads.getValue("openrouter")
        val provider = topology.providers.getValue("openrouter")
        val listed = DiscoveredModel(
            id = "fixture/extra-model",
            contextWindow = 64_000,
            rates = ModelRates(input = 0.5, cacheRead = 0.1, output = 2.0),
        )
        val catalog = provider.catalogFor(head, discovered = listOf(listed))
        assertEquals(provider.models.map { it.id } + listed.id, catalog.models.map { it.id })
        assertEquals(listed.rates, catalog.models.single { it.id == listed.id }.rates)
        assertEquals(64_000L, catalog.contextWindowFor(listed.id))
        provider.models.forEach { row -> assertEquals(row.contextWindow, catalog.contextWindowFor(row.id), row.id) }
        assertEquals(provider.models.first().id, catalog.pinnedModel)
        assertEquals(
            mapOf(
                "anthropic/claude-sonnet-5" to "sonnet",
                "anthropic/claude-opus-5.5" to "opus",
                "z-ai/glm-5.3-flash" to "haiku",
                "openai/gpt-6-sol" to "fable",
            ),
            head.tierSlots(),
        )
        assertEquals(null, head.models)
        assertEquals(null, head.contextWindow)
    }

    @Test
    fun `no profile row exceeds its provider's published window`() {
        // The provider's cached roster, not values derived from AddProfileCatalog.
        val served = mapOf(
            "anthropic/claude-sonnet-5" to 1_000_000L,
            "anthropic/claude-opus-5.5" to 1_000_000L,
            "z-ai/glm-5.3-flash" to 1_048_576L,
            "openai/gpt-6-sol" to 1_050_000L,
            "openai/gpt-6-luna" to 1_050_000L,
            "google/gemini-3.8-flash" to 1_048_576L,
            "deepseek/deepseek-v4.1-flash" to 1_048_576L,
            "z-ai/glm-5.3" to 1_048_576L,
            "meta-llama/llama-4-maverick" to 1_048_576L,
            "anthropic/claude-haiku-4.5" to 200_000L,
        )
        profile.models.forEach { row ->
            assertTrue(row.contextWindow <= served.getValue(row.id), "oversized profile row: ${row.id}")
        }
    }

    @Test
    fun `no model id carries two slots`() {
        val mappings = profile.models.flatMap { model -> model.slots.map { slot -> model.id to slot } }
        val ids = mappings.map { it.first }
        assertEquals(ids.distinct().size, ids.size, "an id repeated across slots cannot load: $mappings")
        val slots = mappings.map { it.second }
        assertEquals(slots.distinct().size, slots.size, "a slot claimed twice cannot load: $mappings")
        assertEquals(listOf("sonnet", "opus", "haiku", "fable"), slots)
    }

    @Test
    fun `the selected profile catalog carries ten models and four unique slots`(@TempDir dir: Path) {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        val topology = TopologyLoader.loadOrMaterialize(path)
        val head = requireNotNull(topology.heads["openrouter"]) { "profile head missing" }
        assertEquals("claude-openrouter", head.claude.command)
        val provider = requireNotNull(topology.providers["openrouter"]) { "profile provider missing" }
        assertEquals(PROFILE_MODELS, provider.models.size)
        val catalog = provider.catalogFor(head)
        assertEquals(PROFILE_MODELS, catalog.models.size)
        assertEquals("anthropic/claude-sonnet-5", catalog.pinnedModel)
        val slots = head.tierSlots().values.toList()
        assertEquals(listOf("sonnet", "opus", "haiku", "fable"), slots)
        assertEquals(slots.distinct().size, slots.size)
        assertTrue(Files.readString(path).contains("command = \"claude-openrouter\""))
    }

    /** V4-228, RED before: opus took anthropic/claude-opus-5 and fable openai/gpt-5.6-sol, a generation
     *  behind what OpenRouter lists. The windows are OpenRouter's own context_length, read 2026-09-25
     *  through `splice models --all`; the selected profile's slots reach those same rows. */
    @Test
    fun `splice add openrouter reaches each family's latest at OpenRouter's window - V4-228`(
        @TempDir dir: Path,
    ) {
        val latest = mapOf(
            "opus" to ("anthropic/claude-opus-5.5" to 1_000_000L),
            "fable" to ("openai/gpt-6-sol" to 1_050_000L),
        )
        val shipped = profile.models.flatMap { m -> m.slots.map { it to (m.id to m.contextWindow) } }.toMap()
        assertEquals(latest, shipped.filterKeys { it in latest })
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        val selected = TopologyLoader.loadOrMaterialize(path)
        val slots = selected.heads.getValue("openrouter").modelSlots
        assertEquals(latest.mapValues { it.value.first }, slots.filterKeys { it in latest })
        val windows = selected.providers.getValue("openrouter").models.associate { it.id to it.contextWindow }
        latest.values.forEach { (id, window) -> assertEquals(window, windows[id], id) }
    }

    /** V4-434, RED before: `splice add openrouter` wrote ten rows with no card, so every turn on a default
     *  model printed "no rate card" on the one route where a user pays per token. The rows are read the way
     *  a head boots them, from the profile's emitted TOML through the loader. */
    private fun shippedRows(dir: Path): List<ModelEntry> {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        return TopologyLoader.loadOrMaterialize(path).providers.getValue("openrouter").models
    }

    @Test
    fun `every openrouter row carries a rate card - V4-434`(@TempDir dir: Path) {
        val rows = shippedRows(dir)
        assertEquals(PROFILE_MODELS, rows.size)
        assertEquals(
            emptyList<String>(),
            rows.filter { it.rates == null }.map { it.id },
            "openrouter rows `splice add` ships with no rate card",
        )
    }

    /** OpenRouter's own cards, GET openrouter.ai/api/v1/models `pricing`, read 2026-09-29 2:05 PM CT
     *  (464 models), per million tokens. Where a card lists no cache write the row has none, and where it
     *  lists no cache read (Llama 4 Maverick) cached tokens bill at the input rate. GPT-6's `overrides`
     *  entry, `min_prompt_tokens` 272000, applies to a request strictly greater than that, which is
     *  LongContextRates' own reading. */
    @Test
    fun `each openrouter row carries OpenRouter's published card - V4-434`(@TempDir dir: Path) {
        val card = { input: Double, read: Double, output: Double, write: Double? ->
            ModelRates(input = input, cacheRead = read, output = output, cacheWrite = write)
        }
        val expected = mapOf(
            "anthropic/claude-sonnet-5" to card(2.0, 0.2, 10.0, 2.5),
            "anthropic/claude-opus-5.5" to card(4.0, 0.2, 20.0, 5.0),
            "z-ai/glm-5.3-flash" to card(0.15, 0.03, 0.5, null),
            "openai/gpt-6-sol" to ModelRates(
                input = 2.0,
                cacheRead = 0.2,
                output = 10.0,
                cacheWrite = 2.5,
                longContext = LongContextRates(272_000, 4.0, 0.4, 15.0, cacheWrite = 5.0),
            ),
            "openai/gpt-6-luna" to ModelRates(
                input = 0.1,
                cacheRead = 0.01,
                output = 0.5,
                cacheWrite = 0.125,
                longContext = LongContextRates(272_000, 0.2, 0.02, 0.75, cacheWrite = 0.25),
            ),
            "google/gemini-3.8-flash" to card(0.75, 0.075, 3.75, 0.0416667),
            "deepseek/deepseek-v4.1-flash" to card(0.3, 0.006, 1.2, null),
            "z-ai/glm-5.3" to card(1.4, 0.26, 4.4, null),
            "meta-llama/llama-4-maverick" to card(0.1875, 0.1875, 0.6525, null),
            "anthropic/claude-haiku-4.5" to card(1.0, 0.1, 5.0, 1.25),
        )
        assertEquals(expected, shippedRows(dir).associate { it.id to it.rates })
    }

    @Test
    fun `the profile ships ten rows and the openrouter wrapper command`() {
        assertEquals(PROFILE_MODELS, profile.models.size)
        assertEquals("claude-openrouter", profile.command)
        assertEquals("openai-chat", profile.dialect)
        assertEquals("api-key", profile.authKind)
        assertEquals("https://openrouter.ai/api/v1", profile.baseUrl)
        assertEquals("OPENROUTER_API_KEY", AddProfiles().apiKeyEnv("openrouter"))
    }
}

private const val PORT = 3101
private const val PROFILE_MODELS = 10
private val DAEMON_BLOCK = """
    [daemon]
    control_port = 3096
""".trimIndent() + "\n"
