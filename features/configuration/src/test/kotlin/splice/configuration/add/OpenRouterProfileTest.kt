// OpenRouter ships a real model surface. Loading the topology is not enough:
// catalogFor is where modelsFor runs, and that is where duplicate-id and unknown-slot refuse boot.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
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

    /** The rows a head boots with: the profile's emitted TOML read through the loader. */
    private fun shippedRows(dir: Path): List<ModelEntry> {
        val path = dir.resolve("splice.toml")
        Files.writeString(path, DAEMON_BLOCK + AddProfiles().toml(profile, "openrouter", PORT))
        return TopologyLoader.loadOrMaterialize(path).providers.getValue("openrouter").models
    }

    @Test
    fun `every openrouter row carries a rate card, since the user pays per token`(@TempDir dir: Path) {
        val rows = shippedRows(dir)
        assertEquals(PROFILE_MODELS, rows.size)
        assertEquals(
            emptyList<String>(),
            rows.filter { it.rates == null }.map { it.id },
            "openrouter rows `splice add` ships with no rate card",
        )
    }
}

private const val PORT = 3101
private const val PROFILE_MODELS = 10
private val DAEMON_BLOCK = """
    [daemon]
    control_port = 3096
""".trimIndent() + "\n"
