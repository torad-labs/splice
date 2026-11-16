// NEW: gpt-speed (docs/specs/gpt-speed.md, decision 5) — a turn is priced at the card for the speed the backend says
// it served it at. The cards come from splice.toml's flat fast_ and ultrafast_ keys, or from the shipped speed table
// beside published-rates.tsv; a speed with no card leaves the turn unpriced, never priced at the standard card.
package splice.configuration.add

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.model.TurnPrice
import splice.core.perf.PerfKeys
import splice.core.speed.Speed
import splice.topology.TopologyLoader

class SpeedRateCardsTest {
    private fun priceOf(baseUrl: String, model: String, rates: String?): TurnPrice {
        val parsed = TopologyLoader.parse(
            """
            [daemon]
            control_port = 3096

            [providers.x]
            dialect = "openai-responses"
            base_url = "$baseUrl"
            auth = { kind = "api-key", env = "X_API_KEY" }
            [[providers.x.models]]
            id = "$model"
            label = "M"
            context_window = 400000
            ${rates?.let { "rates = { $it }" }.orEmpty()}

            [heads.x]
            provider = "x"
            port = 3197
            discovery_prefix = "x--"
            pinned_model = "$model"
            """.trimIndent(),
        )
        return TurnPrice(parsed.providers.getValue("x").catalogFor(parsed.heads.getValue("x")))
    }

    /** A turn of [input] input tokens, none cached, and 100 output tokens, served at [served]. */
    private fun turn(served: Speed?, input: Long = 1_000L): Map<String, Long> = buildMap {
        put(PerfKeys.IN_TOKENS, input)
        put(PerfKeys.CACHED_TOKENS, 0L)
        put(PerfKeys.CACHE_WRITE_TOKENS, 0L)
        put(PerfKeys.OUT_TOKENS, 100L)
        served?.let { put(PerfKeys.SPEED, it.code) }
    }

    private val astraCard = "input = 10.0, cache_read = 1.0, output = 50.0, " +
        "fast_input = 20.0, fast_cache_read = 2.0, fast_output = 100.0"

    @Test
    fun `a turn is priced at the card for the speed it was served at`() {
        val price = priceOf("https://api.example.test/v1", "m", astraCard)

        assertEquals((1_000 * 10.0 + 100 * 50.0) / 1e6, price.usd("m", turn(Speed.STANDARD))!!, 1e-12)
        assertEquals((1_000 * 20.0 + 100 * 100.0) / 1e6, price.usd("m", turn(Speed.FAST))!!, 1e-12)
        // A row from before the speed counters, or from a backend that names no tier, prices as it always did.
        assertEquals((1_000 * 10.0 + 100 * 50.0) / 1e6, price.usd("m", turn(null))!!, 1e-12)
    }

    @Test
    fun `a turn served at a speed with no card is left unpriced`() {
        val price = priceOf("https://api.example.test/v1", "m", astraCard)

        assertNull(price.usd("m", turn(Speed.ULTRAFAST)))
    }

    @Test
    fun `the shipped table prices GPT-6 Astra at Fast and Ultrafast on a ChatGPT plan`() {
        val price = priceOf("https://chatgpt.com/backend-api/codex", "gpt-6-astra", null)

        assertEquals((1_000 * 20.0 + 100 * 100.0) / 1e6, price.usd("gpt-6-astra", turn(Speed.FAST))!!, 1e-12)
        assertEquals((1_000 * 60.0 + 100 * 300.0) / 1e6, price.usd("gpt-6-astra", turn(Speed.ULTRAFAST))!!, 1e-12)
    }

    @Test
    fun `a request past the long-context threshold at a speed is left unpriced`() {
        val price = priceOf("https://chatgpt.com/backend-api/codex", "gpt-6-astra", null)

        // The shipped speed cards are OpenAI's short-context ones, for input at or under 272K tokens.
        assertNull(price.usd("gpt-6-astra", turn(Speed.FAST, input = 300_000L)))
        assertTrue(price.usd("gpt-6-astra", turn(Speed.STANDARD, input = 300_000L))!! > 0.0)
    }

    @Test
    fun `a half-declared speed card is refused where the config is read, naming what is missing`() {
        val failure = assertThrows(Exception::class.java) {
            val halfCard = "input = 10.0, cache_read = 1.0, output = 50.0, fast_input = 20.0"
            priceOf("https://api.example.test/v1", "m", halfCard)
        }
        val messages = generateSequence<Throwable>(failure) { it.cause }.map { it.message.orEmpty() }.toList()
        assertTrue(
            messages.any { "fast_cache_read" in it },
            "a card priced at half a speed must not load silently: $messages",
        )
    }
}
