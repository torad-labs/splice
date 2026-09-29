// NEW: V4-438 — the card an endpoint lists for a model, read into ModelRates. OpenRouter answers `pricing`
// on every row of GET /models (read 2026-09-29, 464 models): prompt and completion per TOKEN as decimal
// strings, input_cache_read and input_cache_write on the models that cache, and overrides[] for a tier that
// starts at min_prompt_tokens (80 models). The bodies below are that shape, cut from the live answer.
package splice.models.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.model.LongContextRates
import splice.core.model.ModelRates

class ListedPricingTest {

    private fun ratesOf(pricing: String?): ModelRates? {
        val block = pricing?.let { ""","pricing":$it""" }.orEmpty()
        val body = """{"data":[{"id":"vendor/model"$block}]}"""
        val roster = UpstreamRosterParser().parse(body, "https://openrouter.ai/api/v1/models")
        return (roster as UpstreamRoster.Published).models.single().rates
    }

    @Test
    fun `a listed card is read per million tokens with the cache prices where OpenRouter lists them`() {
        // anthropic/claude-sonnet-5 as OpenRouter lists it: web_search and the 1h write are not in a card.
        val card = ratesOf(
            """{"prompt":"0.000002","completion":"0.00001","web_search":"0.01","input_cache_read":"0.0000002",""" +
                """"input_cache_write":"0.0000025","input_cache_write_1h":"0.000004"}""",
        )
        assertEquals(ModelRates(input = 2.0, cacheRead = 0.2, output = 10.0, cacheWrite = 2.5), card)
    }

    @Test
    fun `a model that lists no cache price bills reads and writes at its input price`() {
        // meta-llama/llama-4-maverick: prompt and completion only. Reads at input is V4-434's rule for the
        // same row written by hand, and a null write price is what ModelRates means by "bills at input".
        val card = ratesOf("""{"prompt":"0.0000001875","completion":"0.0000006525"}""")
        assertEquals(ModelRates(input = 0.1875, cacheRead = 0.1875, output = 0.6525), card)
    }

    @Test
    fun `an override that starts at a prompt size is the long-context tier`() {
        // openai/gpt-6-sol: 272000 is min_prompt_tokens and OpenRouter's doc says a request strictly over it
        // takes the override, which is what LongContextRates.overInputTokens means.
        val card = ratesOf(
            """{"prompt":"0.000002","completion":"0.00001","input_cache_read":"0.0000002",""" +
                """"input_cache_write":"0.0000025",""" +
                """"overrides":[{"min_prompt_tokens":272000,"prompt":"0.000004","completion":"0.000015",""" +
                """"input_cache_read":"0.0000004","input_cache_write":"0.000005"}]}""",
        )
        assertEquals(
            ModelRates(
                input = 2.0,
                cacheRead = 0.2,
                output = 10.0,
                cacheWrite = 2.5,
                longContext = LongContextRates(
                    overInputTokens = 272_000,
                    input = 4.0,
                    cacheRead = 0.4,
                    output = 15.0,
                    cacheWrite = 5.0,
                ),
            ),
            card,
        )
    }

    @Test
    fun `a tier with no cache write price of its own bills its writes at its input price`() {
        val card = ratesOf(
            """{"prompt":"0.000001","completion":"0.000002","input_cache_read":"0.0000001",""" +
                """"input_cache_write":"0.00000125","overrides":[{"min_prompt_tokens":200000,"prompt":"0.000002",""" +
                """"completion":"0.000004","input_cache_read":"0.0000002"}]}""",
        )
        val tier = LongContextRates(200_000, input = 2.0, cacheRead = 0.2, output = 4.0, cacheWrite = null)
        assertEquals(tier, card?.longContext)
    }

    @Test
    fun `two size tiers keep the lowest threshold, the one most requests past a boundary reach`() {
        // qwen/qwen3.7-flash lists 256000 and 32000. A card carries one tier, so a request past the higher
        // threshold is priced at the lower tier's card: an underestimate, on five models of 464.
        val card = ratesOf(
            """{"prompt":"0.00000003","completion":"0.00000013","overrides":[""" +
                """{"min_prompt_tokens":256000,"prompt":"0.0000002","completion":"0.0000008"},""" +
                """{"min_prompt_tokens":32000,"prompt":"0.0000001","completion":"0.0000004"}]}""",
        )
        assertEquals(LongContextRates(32_000, input = 0.1, cacheRead = 0.1, output = 0.4), card?.longContext)
    }

    @Test
    fun `an override that names a time of day and no size is not a tier`() {
        // tencent/hy3 lists overrides with utc_start and utc_end: a peak price, which no request size selects.
        val card = ratesOf(
            """{"prompt":"0.000001","completion":"0.000002","overrides":[""" +
                """{"utc_start":"08:00","utc_end":"20:00","prompt":"0.000002","completion":"0.000004"}]}""",
        )
        assertEquals(ModelRates(input = 1.0, cacheRead = 1.0, output = 2.0), card)
    }

    @Test
    fun `a listed zero is a price, so a free model reads as costing nothing`() {
        val free = ratesOf("""{"prompt":"0","completion":"0"}""")
        assertEquals(ModelRates(input = 0.0, cacheRead = 0.0, output = 0.0), free)
    }

    @Test
    fun `a listing splice cannot price has no card, and is never a guess`() {
        // openrouter/auto lists -1 for a price that depends on the route it picks.
        assertNull(ratesOf("""{"prompt":"-1","completion":"-1"}"""))
        assertNull(ratesOf(null), "no pricing block")
        assertNull(ratesOf("""{"prompt":"0.000002"}"""), "no completion price")
        assertNull(ratesOf("""{"prompt":"cheap","completion":"0.00001"}"""), "a price that is not a number")
        assertNull(ratesOf("""{"prompt":"0.000002","completion":"-1"}"""), "one side dynamic")
        assertNull(ratesOf("""["prompt"]"""), "a pricing value that is not an object")
    }
}
