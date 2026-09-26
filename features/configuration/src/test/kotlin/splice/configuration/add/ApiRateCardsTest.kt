// NEW: V4-240, the film's four models get the vendor's published API card from `splice add`, so a fresh
// config prices them. Driven the way DeepSeekProfileTest drives its card: the profile's real emitted
// TOML, parsed, then through catalogFor, where a head boots. The long-context tier rides flat on the
// inline card as `long_context_` keys (ModelRatesToml), and must come back whole, or grok-4.7 bills a
// 300k turn at the base card.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.LongContextRates
import splice.core.model.ModelCatalog
import splice.core.model.ModelRates
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.topology.TopologyLoader

class ApiRateCardsTest {

    private fun catalogOf(profileName: String, headKey: String): ModelCatalog {
        val profile = requireNotNull(AddProfiles().find(profileName)) { "$profileName profile missing" }
        val emitted = AddProfiles().toml(profile, headKey, PORT)
        val parsed = TopologyLoader.parse("[daemon]\ncontrol_port = 3096\n$emitted")
        return parsed.providers.getValue(headKey).catalogFor(parsed.heads.getValue(headKey))
    }

    private fun cardOf(profileName: String, headKey: String, modelId: String): ModelRates? {
        val catalog = catalogOf(profileName, headKey)
        return catalog.models.first { catalog.stripSuffixes(it.id) == modelId }.rates
    }

    @Test
    fun `gpt-6-sol carries OpenAI's card and its over-272K tier`() {
        val tier = LongContextRates(
            overInputTokens = 272_000,
            input = 4.0,
            cacheRead = 0.4,
            output = 15.0,
            cacheWrite = 5.0,
        )
        assertEquals(
            ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 2.5, longContext = tier),
            cardOf("codex", "codex", "gpt-6-sol"),
        )
    }

    @Test
    fun `grok-4_7 carries xAI's card and its 200k tier`() {
        val tier = LongContextRates(overInputTokens = 199_999, input = 4.0, cacheRead = 1.0, output = 12.0)
        assertEquals(
            ModelRates(input = 2.0, cacheRead = 0.5, output = 6.0, longContext = tier),
            cardOf("grok", "grok", "grok-4.7"),
        )
    }

    @Test
    fun `muse-spark-1_3 carries Meta's Standard card, with no tier`() {
        assertEquals(
            ModelRates(input = 1.25, cacheRead = 0.15, output = 4.25),
            cardOf("muse", "muse", "muse-spark-1.3"),
        )
    }

    @Test
    fun `claude-opus-5-5 carries Anthropic's card at the 1-hour cache-write rate`() {
        assertEquals(
            ModelRates(input = 4.0, cacheRead = 0.2, output = 20.0, cacheWrite = 8.0),
            cardOf("claude", "claude-splice", "claude-opus-5-5"),
        )
    }

    // V4-270: a default claude-splice install takes Claude Code's small-fast turns on Haiku 4.5 and /model
    // sonnet or fable turns on those rows, and each one with no card counted as "1 turn unpriced".
    @Test
    fun `every model the claude profile lists carries a rate card`() {
        val catalog = catalogOf("claude", "claude-splice")
        val unpriced = catalog.models.filter { it.rates == null }.map { catalog.stripSuffixes(it.id) }.distinct()
        assertEquals(emptyList<String>(), unpriced, "models the claude profile lists with no rate card")
    }

    @Test
    fun `fable 5_1, sonnet 5 and haiku 4_5 carry Anthropic's cards at the 1-hour cache-write rate`() {
        assertEquals(
            ModelRates(input = 10.0, cacheRead = 0.25, output = 50.0, cacheWrite = 20.0),
            cardOf("claude", "claude-splice", "claude-fable-5-1"),
        )
        assertEquals(
            ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 4.0),
            cardOf("claude", "claude-splice", "claude-sonnet-5"),
        )
        assertEquals(
            ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 2.0),
            cardOf("claude", "claude-splice", "claude-haiku-4-5"),
        )
    }

    // The team tally (TeamsEconomics.kt PerfTally.rates) and TurnPrice resolve a turn's card the same way:
    // the stripped model id against the head's catalog. perf rows record the small-fast turn as
    // claude-haiku-4-5, so one such turn must come back priced, not null.
    @Test
    fun `one Haiku turn on the claude profile is priced`() {
        val counters = mapOf(
            PerfKeys.IN_TOKENS to 1_000_000L,
            PerfKeys.CACHED_TOKENS to 0L,
            PerfKeys.CACHE_WRITE_TOKENS to 0L,
            PerfKeys.OUT_TOKENS to 1_000_000L,
        )
        assertEquals(6.0, TurnPrice(catalogOf("claude", "claude-splice")).usd("claude-haiku-4-5", counters))
    }

    @Test
    fun `a half-declared tier is refused where the config is read, naming what is missing`() {
        val halfTier = """
            [daemon]
            control_port = 3096

            [providers.x]
            dialect = "openai-responses"
            base_url = "https://api.example.test/v1"
            auth = { kind = "api-key", env = "X_API_KEY" }
            [[providers.x.models]]
            id = "m"
            label = "M"
            context_window = 500000
            rates = { input = 2.0, cache_read = 0.5, output = 6.0, long_context_input = 4.0 }
        """.trimIndent()
        val failure = assertThrows(Exception::class.java) { TopologyLoader.parse(halfTier) }
        val messages = generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }.toList()
        assertTrue(
            messages.any { "long_context_over_input_tokens" in it },
            "a card priced at half a tier must not load silently: $messages",
        )
    }
}

private const val PORT = 3101
