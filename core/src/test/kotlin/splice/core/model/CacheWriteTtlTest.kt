// NEW: Oct 10, 2026 review — a prompt cache written for an HOUR bills above one written for five minutes, at
// every vendor that publishes both (Anthropic 2x input against 1.25x, Moonshot K3 6 against 3). The shipped
// list carried one write price and the usage collapsed both durations, so every hourly write was charged the
// five-minute rate: 100k hourly writes on Opus 5.5 billed $0.50 instead of $0.80, and a $0.60 budget stayed
// open on a turn that had already passed it. Each write now bills at the rate its own duration earns.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.perf.PerfKeys
import splice.core.topology.AuthConfig
import splice.core.topology.Dialect
import splice.core.topology.HeadConfig
import splice.core.topology.ProviderConfig
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** A forwarded Claude head: the client picks the model and splice prices it from the shipped list. */
private fun claudeHead(model: String): TurnPrice {
    val provider = ProviderConfig(
        dialect = Dialect.ANTHROPIC_PASSTHROUGH,
        baseUrl = "https://api.anthropic.com",
        auth = AuthConfig("client"),
        models = listOf(ModelEntry(model, label = model, contextWindow = 1_000_000)),
    )
    return TurnPrice(provider.catalogFor(HeadConfig("claude-splice", 3098, "claude-splice--", model)))
}

/** One turn whose whole input was written to the cache, [hourly] of it held for an hour. A write is a disjoint
 *  part of in_tokens, so a turn that only wrote cache reports no fresh input; the key is absent when there is
 *  no hourly share, which is how every row written before the counter reads. */
private fun writeRow(written: Long, hourly: Long, output: Long = 0L): Map<String, Long> = buildMap {
    put(PerfKeys.IN_TOKENS, written)
    put(PerfKeys.OUT_TOKENS, output)
    put(PerfKeys.CACHED_TOKENS, 0L)
    put(PerfKeys.CACHE_WRITE_TOKENS, written)
    if (hourly > 0) put(PerfKeys.CACHE_WRITE_1H_TOKENS, hourly)
}

class CacheWriteTtlTest {

    private val cost = TokenCost()

    @Test
    fun `an hourly write bills at the hourly price and a five-minute write at the cheaper one`() {
        val price = claudeHead("claude-opus-5-5")
        val tokens = 100_000L

        // Opus 5.5: $5 per million five-minute write tokens, $8 hourly (platform.claude.com pricing).
        assertEquals(0.8, price.usd("claude-opus-5-5", writeRow(tokens, hourly = tokens))!!, 1e-9)
        assertEquals(0.5, price.usd("claude-opus-5-5", writeRow(tokens, hourly = 0))!!, 1e-9)
    }

    @Test
    fun `a turn that wrote at both durations pays each share at its own rate`() {
        val price = claudeHead("claude-opus-5-5")

        // 60k at $5 and 40k at $8: 0.30 + 0.32. The hourly count is a SHARE of the write bucket, so the
        // five-minute write is the remainder and no token is charged twice or dropped.
        assertEquals(0.62, price.usd("claude-opus-5-5", writeRow(100_000, hourly = 40_000))!!, 1e-9)
    }

    @Test
    fun `a row from before the counter prices exactly as it did`() {
        val price = claudeHead("claude-sonnet-5")
        val old = mapOf(
            PerfKeys.IN_TOKENS to 1_000_000L,
            PerfKeys.OUT_TOKENS to 0L,
            PerfKeys.CACHED_TOKENS to 0L,
            PerfKeys.CACHE_WRITE_TOKENS to 1_000_000L,
        )

        // Sonnet 5's five-minute write is $2.50, which is what a row with no reported TTL has always paid.
        assertEquals(2.5, price.usd("claude-sonnet-5", old)!!, 1e-9)
    }

    @Test
    fun `a card with one write price charges both durations at it, and a card with none charges input`() {
        val onePrice = ModelRates(input = 3.0, cacheRead = 0.3, output = 15.0, cacheWrite = 3.75)
        val noWritePrice = ModelRates(input = 3.0, cacheRead = 0.3, output = 15.0)
        val buckets = TokenBuckets(cacheWrite = 1_000_000, cacheWriteHourly = 400_000)

        assertEquals(3.75, cost.of(buckets, onePrice), 1e-9)
        assertEquals(3.0, cost.of(buckets, noWritePrice), 1e-9, "a write never costs less than a cache miss")
    }

    @Test
    fun `a long request takes the tier's own hourly write`() {
        val price = claudeHead("claude-haiku-5-5")

        // Haiku 5.5 bills its higher card for a prompt over 100,000 tokens: the tier's five-minute write is
        // $0.625 and its hourly one $1. 200k hourly write tokens reach the tier on size alone.
        assertEquals(0.2, price.usd("claude-haiku-5-5", writeRow(200_000, hourly = 200_000))!!, 1e-9)
        assertEquals(0.125, price.usd("claude-haiku-5-5", writeRow(200_000, hourly = 0))!!, 1e-9)
    }

    @Test
    fun `an hourly share larger than the write bucket cannot bill more than the bucket`() {
        val card = ModelRates(input = 1.0, cacheRead = 0.1, output = 5.0, cacheWrite = 1.25, cacheWriteHourly = 2.0)
        val malformed = TokenBuckets(cacheWrite = 1_000_000, cacheWriteHourly = 9_000_000)

        // The hourly count is a share, so it is held to the bucket: the whole write bills hourly, once.
        assertEquals(2.0, cost.of(malformed, card), 1e-9)
    }

    @Test
    fun `a vendor that bills by the hour scales both write prices`() {
        val offPeak = ModelRates(
            input = 1.0,
            cacheRead = 0.1,
            output = 5.0,
            cacheWrite = 1.25,
            peak = PeakHours(factor = 2.0, hoursUtc = listOf(1 until 4), weekdaysOnly = false),
            cacheWriteHourly = 2.0,
        )
        val atTwoUtc = ZonedDateTime.of(2026, 10, 7, 2, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val peak = offPeak.at(atTwoUtc)

        assertEquals(2.5, peak.cacheWrite!!, 1e-9)
        assertEquals(4.0, peak.cacheWriteHourly!!, 1e-9)
        assertEquals(2.0, offPeak.at(atTwoUtc + 3 * 3_600_000L).cacheWriteHourly!!, 1e-9, "05:00 is off-peak")
    }

    @Test
    fun `the rounds a turn absorbed pay their own hourly writes`() {
        val price = claudeHead("claude-opus-5-5")
        val row = writeRow(100_000, hourly = 100_000) + mapOf(
            PerfKeys.ABSORBED_ROUNDS to 1L,
            PerfKeys.ABSORBED_IN_TOKENS to 50_000L,
            PerfKeys.ABSORBED_CACHED_TOKENS to 0L,
            PerfKeys.ABSORBED_CACHE_WRITE_TOKENS to 50_000L,
            PerfKeys.ABSORBED_CACHE_WRITE_1H_TOKENS to 50_000L,
            PerfKeys.ABSORBED_OUT_TOKENS to 0L,
        )

        // The final round's 100k and the absorbed round's 50k, both hourly: 0.80 + 0.40.
        assertEquals(1.2, price.usd("claude-opus-5-5", row)!!, 1e-9)
    }
}
