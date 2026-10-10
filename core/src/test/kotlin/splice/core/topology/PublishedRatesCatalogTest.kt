// NEW: Oct 10, 2026 — a fresh install prices its turns: a model on its vendor's own host takes the list price
// splice ships, last in the order head card, row card, the provider's listed price, the shipped price.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.DiscoveredModel
import splice.core.model.LongContextRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates

class PublishedRatesCatalogTest {

    private val written = ModelRates(input = 9.0, cacheRead = 0.9, output = 90.0)

    private fun xai(vararg models: ModelEntry, baseUrl: String = "https://api.x.ai/v1") = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = baseUrl,
        auth = AuthConfig("api-key", env = "XAI_API_KEY"),
        models = models.toList(),
    )

    private val head = HeadConfig("claude-grok", 4104, "claude-grok--", "grok-4.7")

    private fun ratesOf(catalog: ModelCatalog, id: String): ModelRates? =
        catalog.models.first { it.id == id }.rates

    @Test
    fun `a model on its vendor's host takes the vendor's published price, long-context tier included`() {
        val catalog = xai(ModelEntry("grok-4.7", label = "Grok 4.7", contextWindow = 500_000)).catalogFor(head)
        val tier = LongContextRates(overInputTokens = 199_999, input = 4.0, cacheRead = 1.0, output = 12.0)
        val card = ModelRates(input = 2.0, cacheRead = 0.5, output = 6.0, longContext = tier)
        assertEquals(card, ratesOf(catalog, "grok-4.7"))
    }

    @Test
    fun `a row behind a tier suffix and a discovered model are priced under their bare ids`() {
        val catalog = xai(ModelEntry("grok-4.3[1m]", label = "Grok 4.3 (1M)", contextWindow = 1_000_000))
            .catalogFor(
                HeadConfig("claude-grok", 4104, "claude-grok--", "grok-4.3[1m]"),
                discovered = listOf(DiscoveredModel("grok-build-0.1", contextWindow = 256_000)),
            )
        assertEquals(1.25, ratesOf(catalog, "grok-4.3[1m]")?.input)
        assertEquals(1.0, ratesOf(catalog, "grok-build-0.1")?.input)
    }

    @Test
    fun `a card somebody wrote or the provider listed wins over the shipped price`() {
        val listed = ModelRates(input = 3.0, cacheRead = 0.3, output = 7.0)
        val catalog = xai(
            ModelEntry("grok-4.7", label = "Grok 4.7", contextWindow = 500_000, rates = written),
            ModelEntry("grok-4.6", label = "Grok 4.6", contextWindow = 500_000),
        ).catalogFor(head, discovered = listOf(DiscoveredModel("grok-4.6", rates = listed)))
        assertEquals(written, ratesOf(catalog, "grok-4.7"))
        assertEquals(listed, ratesOf(catalog, "grok-4.6"))
    }

    @Test
    fun `the same model id on another host, and a model the vendor publishes no price for, stay unpriced`() {
        val elsewhere = xai(
            ModelEntry("grok-4.7", label = "Grok 4.7", contextWindow = 500_000),
            baseUrl = "http://127.0.0.1:8100/v1",
        ).catalogFor(head)
        assertNull(ratesOf(elsewhere, "grok-4.7"))
        val unknown = xai(ModelEntry("grok-9", label = "Grok 9", contextWindow = 500_000))
            .catalogFor(HeadConfig("claude-grok", 4104, "claude-grok--", "grok-9"))
        assertNull(ratesOf(unknown, "grok-9"))
    }
}
