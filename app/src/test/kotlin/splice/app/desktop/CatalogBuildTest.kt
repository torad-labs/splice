// The model catalog built from signed-in, configured providers: what each provider's own list publishes, once per
// (provider, id), credential-free, and rewritten rather than merged across a restart (CatalogFile, StatePaths
// .modelCatalogFile).
package splice.app.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelListing
import splice.core.model.ModelRuntimeMetadata
import splice.core.util.LogSink
import java.nio.file.Path

internal class CatalogBuildTest {
    @Test
    fun `a model its provider lists carries that list's name, description and reasoning levels`() {
        val listed = DiscoveredModel(
            id = "gpt-6.2-sol",
            label = "GPT-6.2 Sol",
            runtimeMetadata = ModelRuntimeMetadata(
                listing = ModelListing(
                    description = "the chat backend's flagship",
                    efforts = listOf("low", "medium", "high"),
                    defaultEffort = "medium",
                    reasons = true,
                ),
            ),
        )
        val entry = ModelEntry(id = "gpt-6.2-sol", label = "sol", contextWindow = 272_000, listed = listed)
        val catalog = catalogOf(entry)

        val built = CatalogBuild().of(listOf(CatalogSource("codex", catalog)))

        assertEquals(1, built.models.size)
        val model = built.models.single()
        assertEquals("codex", model.provider)
        assertEquals("gpt-6.2-sol", model.id)
        assertEquals("GPT-6.2 Sol", model.label)
        assertEquals("the chat backend's flagship", model.description)
        assertEquals(listOf("low", "medium", "high"), model.reasoningLevels)
        assertEquals("medium", model.defaultReasoningLevel)
        assertTrue(model.reasons)
    }

    @Test
    fun `a model no list names carries splice toml's label and nothing guessed about its reasoning`() {
        val entry = ModelEntry(id = "acme-coder", label = "Acme Coder", contextWindow = 128_000)
        val catalog = catalogOf(entry)

        val built = CatalogBuild().of(listOf(CatalogSource("work", catalog)))

        val model = built.models.single()
        assertEquals("Acme Coder", model.label)
        assertEquals("", model.description)
        assertEquals(emptyList<String>(), model.reasoningLevels)
        assertEquals(null, model.defaultReasoningLevel)
        assertEquals(false, model.reasons)
    }

    @Test
    fun `a curated name replaces an id-shaped provider label but not a meaningful provider name`() {
        val raw = ModelEntry(
            id = "k3-256k",
            label = "Kimi K3 (256k)",
            contextWindow = 256_000,
            listed = DiscoveredModel(id = "k3-256k", label = "K3-256k"),
        )
        val current = ModelEntry(
            id = "kimi-for-coding",
            label = "Kimi K2.7 Code",
            contextWindow = 256_000,
            listed = DiscoveredModel(id = "kimi-for-coding", label = "K2.8 Preview"),
        )

        val models = CatalogBuild().of(listOf(CatalogSource("kimi", catalogOf(raw, current)))).models

        assertEquals(listOf("Kimi K3 (256k)", "K2.8 Preview"), models.map { it.label })
    }

    @Test
    fun `a model with no published or configured label gets one readable name in the catalog`() {
        val entry = ModelEntry(id = "grok-4.20-multi-agent", contextWindow = 256_000)
        val model = CatalogBuild().of(listOf(CatalogSource("xai", catalogOf(entry)))).models.single()

        assertEquals("Grok 4.20 Multi Agent", model.label)
    }

    @Test
    fun `custom model file names become readable while a configured name is preserved`() {
        val id = "/models/Inkling-2-Small-e4853745-Q3_K_M-imatrix.gguf"
        val raw = ModelEntry(id = id, label = id.substringAfterLast('/'), contextWindow = 256_000)
        val named = raw.copy(label = "Inkling 2 Small (local)")

        assertEquals(
            "Inkling 2 Small",
            CatalogBuild().of(listOf(CatalogSource("local", catalogOf(raw)))).models.single().label,
        )
        assertEquals(
            "Inkling 2 Small (local)",
            CatalogBuild().of(listOf(CatalogSource("local", catalogOf(named)))).models.single().label,
        )
    }

    @Test
    fun `Meta's listed Muse models have names and descriptions without adding or removing models`() {
        val ids = listOf(
            "muse-wonder-full-1.0", "muse-spark-1.3", "muse-spark-1.2", "muse-ember-1.0",
            "assetgen-nano-1.0", "assetgen-pro-1.0", "muse-wonder-full-1.0-contributor",
            "muse-wonder-1.0-contributor", "muse-wonder-1.0", "muse-spark-1.3-contributor",
            "muse-spark-1.2-contributor", "muse-spark-1.1",
        )
        val catalog = catalogOf(*ids.map { ModelEntry(it, contextWindow = 1_000_000) }.toTypedArray())
            .copy(vendorBaseUrl = "https://api.meta.ai/v1")

        val models = CatalogBuild().of(listOf(CatalogSource("muse", catalog))).models

        assertEquals(ids, models.map { it.id })
        assertTrue(models.all { it.label.isNotBlank() && it.description.isNotBlank() })
        assertEquals("AssetGen Nano 1.0", models.first { it.id == "assetgen-nano-1.0" }.label)
        assertTrue(models.filter { it.id.endsWith("-contributor") }.all { "train" in it.description })
        val spark = models.first { it.id == "muse-spark-1.3" }
        assertEquals(listOf("minimal", "low", "medium", "high", "xhigh", "max"), spark.reasoningLevels)
        assertEquals("high", spark.defaultReasoningLevel)
        assertEquals(emptyList<String>(), models.first { it.id == "assetgen-pro-1.0" }.reasoningLevels)
    }

    @Test
    fun `provider and configured details win over the known model catalog and are scoped to the host`() {
        val id = "muse-ember-1.0"
        val entry = ModelEntry(
            id,
            label = "My Ember",
            description = "Configured description",
            contextWindow = 1_000_000,
            listed = DiscoveredModel(
                id = id,
                label = "Provider Ember",
                runtimeMetadata = ModelRuntimeMetadata(listing = ModelListing(description = "Provider description")),
            ),
        )
        val catalog = catalogOf(entry).copy(vendorBaseUrl = "https://api.meta.ai/v1")
        val published = CatalogBuild().of(listOf(CatalogSource("muse", catalog))).models.single()
        assertEquals("Provider Ember", published.label)
        assertEquals("Provider description", published.description)

        val configuredCatalog = catalog.copy(models = listOf(entry.copy(listed = null)))
        val configured = CatalogBuild().of(listOf(CatalogSource("muse", configuredCatalog))).models.single()
        assertEquals("My Ember", configured.label)
        assertEquals("Configured description", configured.description)

        val other = catalogOf(ModelEntry(id, contextWindow = 1_000_000))
            .copy(vendorBaseUrl = "https://other.example/v1")
        assertEquals("", CatalogBuild().of(listOf(CatalogSource("other", other))).models.single().description)
    }

    @Test
    fun `one model two providers serve is listed once per provider, never collapsed across them`() {
        val a = catalogOf(ModelEntry(id = "claude-opus-5-5", contextWindow = 200_000))
        val b = catalogOf(ModelEntry(id = "claude-opus-5-5", contextWindow = 200_000))

        val built = CatalogBuild().of(listOf(CatalogSource("claudex", a), CatalogSource("claude-grok", b)))

        assertEquals(setOf("claudex", "claude-grok"), built.models.map { it.provider }.toSet())
        assertEquals(2, built.models.size)
    }

    @Test
    fun `a provider's own 1M-tagged row over the same model is listed once, on the first`() {
        val catalog = catalogOf(
            ModelEntry(id = "k3", contextWindow = 256_000),
            ModelEntry(id = "k3[1m]", contextWindow = 1_000_000),
        )

        val built = CatalogBuild().of(listOf(CatalogSource("kimi", catalog)))

        assertEquals(1, built.models.size)
        assertEquals("k3", built.models.single().id)
    }

    @Test
    fun `a restart's catalog replaces the file's content rather than merging into it`(@TempDir dir: Path) {
        val file = dir.resolve("model-catalog.json")
        val oldRow = ModelEntry(id = "gpt-old", contextWindow = 1)
        val first = CatalogBuild().of(listOf(CatalogSource("codex", catalogOf(oldRow))))
        CatalogFile(file, LogSink {}).keep(first)

        val newRow = ModelEntry(id = "gpt-new", contextWindow = 1)
        val second = CatalogBuild().of(listOf(CatalogSource("codex", catalogOf(newRow))))
        CatalogFile(file, LogSink {}).keep(second)

        val reread = CatalogFile(file, LogSink {}).get()
        assertEquals(listOf("gpt-new"), reread.models.map { it.id })
    }

    private fun catalogOf(vararg entries: ModelEntry): ModelCatalog =
        ModelCatalog(discoveryPrefix = "test--", models = entries.toList(), defaultContextWindow = 128_000)
}
