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
