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
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import java.time.ZoneOffset
import java.time.ZonedDateTime

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

    /** A Claude head forwards the client's own login, so Claude Code picks the models and a turn arrives on
     *  an id no row names. The card for that id ships in the binary, and reading it only off the rows left
     *  every such turn unpriced — a budget stayed open on spending it could not see. */
    @Test
    fun `a turn on a model the roster does not name takes the price its vendor publishes`() {
        val forwarded = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.anthropic.com",
            auth = AuthConfig("client"),
            models = listOf(ModelEntry("claude-sonnet-5", label = "Claude Sonnet 5", contextWindow = 1_000_000)),
        )
        val head = HeadConfig("claude-splice", 3098, "claude-splice--", "claude-sonnet-5")
        val price = TurnPrice(forwarded.catalogFor(head))
        // A million fresh input tokens and a million output: Opus 5.5 at 4 + 20, and it names no row here.
        val turn = mapOf(
            PerfKeys.IN_TOKENS to 1_000_000L,
            PerfKeys.OUT_TOKENS to 1_000_000L,
            PerfKeys.CACHED_TOKENS to 0L,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
        )

        assertEquals(true, price.declares("claude-opus-5-5"), "the shipped card is the head's answer for it")
        assertEquals(24.0, price.usd("claude-opus-5-5", turn)!!, 1e-9)
        assertEquals(12.0, price.usd("claude-sonnet-5", turn)!!, 1e-9, "a declared row prices as before")
        assertNull(price.usd("some-model-nobody-publishes", turn), "an id no vendor publishes stays unpriced")
    }

    @Test
    fun `a DeepSeek turn is priced at the card for the hour it ran in, peak on weekday mornings UTC`() {
        val deepseek = ProviderConfig(
            dialect = Dialect.ANTHROPIC_PASSTHROUGH,
            baseUrl = "https://api.deepseek.com/anthropic",
            auth = AuthConfig("api-key", env = "DEEPSEEK_API_KEY"),
            models = listOf(ModelEntry("deepseek-flash", label = "DeepSeek V4.1 Flash", contextWindow = 1_000_000)),
        )
        val head = HeadConfig("claude-deepseek", 3107, "claude-deepseek--", "deepseek-flash")
        val price = TurnPrice(deepseek.catalogFor(head))
        // A million fresh input tokens and a million output tokens: 0.15 + 0.60 off-peak.
        val turn = mapOf(
            PerfKeys.IN_TOKENS to 1_000_000L,
            PerfKeys.OUT_TOKENS to 1_000_000L,
            PerfKeys.CACHED_TOKENS to 0L,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
        )

        // Oct 7, 2026 is a Wednesday and Oct 10 a Saturday.
        fun usdAt(day: Int, hour: Int, minute: Int = 0): Double {
            val at = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
            return price.usd("deepseek-flash", turn, at)!!
        }

        assertEquals(1.5, usdAt(day = 7, hour = 2), 1e-9, "Wednesday 02:00 UTC is peak")
        assertEquals(1.5, usdAt(day = 7, hour = 9, minute = 59), 1e-9, "09:59 is peak")
        assertEquals(0.75, usdAt(day = 7, hour = 4), 1e-9, "04:00 is off-peak")
        assertEquals(0.75, usdAt(day = 7, hour = 10), 1e-9, "10:00 is off-peak")
        assertEquals(0.75, usdAt(day = 10, hour = 2), 1e-9, "Saturday is off-peak")
    }
}
