// NEW: V4-438 — a head's catalog prices a model wherever the provider's list carries a card for it. The card
// (DiscoveredModel.rates, OpenRouter's `pricing` read at daemon start) fills a row that has none: a model the
// daemon discovered, and a row declared by id with no `rates`, which is how a user adds a model. It never
// replaces a card somebody wrote: the row's own beats the listing's, and the head's own beats both.
package splice.core.topology

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.DiscoveredModel
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.model.ModelRates

class ListedRatesCatalogTest {

    private val written = ModelRates(input = 9.0, cacheRead = 0.9, output = 90.0)
    private val listed = ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 2.5)

    private val provider = ProviderConfig(
        dialect = Dialect.OPENAI_CHAT,
        baseUrl = "https://openrouter.ai/api/v1",
        auth = AuthConfig("api-key", env = "OPENROUTER_API_KEY"),
        models = listOf(
            ModelEntry("acme/bare", label = "Bare", contextWindow = 200_000),
            ModelEntry("acme/written", label = "Written", contextWindow = 200_000, rates = written),
            ModelEntry("acme/tiered[1m]", label = "Tiered", contextWindow = 1_000_000),
        ),
    )
    private val head = HeadConfig("openrouter", 4104, "claude-openrouter--", "acme/bare")

    private fun ratesOf(catalog: ModelCatalog, id: String): ModelRates? =
        catalog.models.first { it.id == id }.rates

    @Test
    fun `a declared row with no rates takes the card the provider lists for it`() {
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("acme/bare", rates = listed)))
        assertEquals(listed, ratesOf(catalog, "acme/bare"))
    }

    @Test
    fun `a listed card reaches a row that stands behind a tier suffix, under its bare id`() {
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("acme/tiered", rates = listed)))
        assertEquals(listed, ratesOf(catalog, "acme/tiered[1m]"))
    }

    @Test
    fun `a discovered model takes its listed card, and one the provider lists no price for stays unpriced`() {
        val catalog = provider.catalogFor(
            head,
            discovered = listOf(
                DiscoveredModel("acme/new", contextWindow = 300_000, rates = listed),
                DiscoveredModel("acme/router", contextWindow = 300_000),
            ),
        )
        assertEquals(listed, ratesOf(catalog, "acme/new"))
        assertNull(ratesOf(catalog, "acme/router"))
    }

    @Test
    fun `a card written on the row wins over the listing`() {
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("acme/written", rates = listed)))
        assertEquals(written, ratesOf(catalog, "acme/written"))
    }

    @Test
    fun `a head's own card wins over the listing and over the row`() {
        val markup = ModelRates(input = 3.0, cacheRead = 0.3, output = 15.0)
        val priced = HeadConfig(
            "openrouter",
            4104,
            "claude-openrouter--",
            "acme/bare",
            rates = mapOf("acme/bare" to markup, "acme/written" to markup),
        )
        val catalog = provider.catalogFor(
            priced,
            discovered = listOf(
                DiscoveredModel("acme/bare", rates = listed),
                DiscoveredModel("acme/written", rates = listed),
            ),
        )
        assertEquals(markup, ratesOf(catalog, "acme/bare"))
        assertEquals(markup, ratesOf(catalog, "acme/written"))
    }

    @Test
    fun `a listing with no card leaves a declared row exactly as it was`() {
        val catalog = provider.catalogFor(head, discovered = listOf(DiscoveredModel("acme/bare")))
        assertNull(ratesOf(catalog, "acme/bare"))
        assertEquals(provider.catalogFor(head).models, catalog.models)
    }
}
