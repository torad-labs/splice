// V4-37 — the rate card, the arithmetic, and the resolution order it is read through.
//
// The numbers in the hand-worked test are REAL, not invented: they are the 67-turn totals measured
// off the live claude-deepseek-perf.jsonl that produced the operator's report (fresh input 305460,
// cache read 6911360, output 46897). At DeepSeek's off-peak flash card the sum is 0.09469128 USD —
// the same figure the report rounded to 0.09 — while the Anthropic Sonnet card the CLIENT applies
// gives 3.69. That 20x is the bug, and pinning this arithmetic is what keeps a rate edit from
// silently reintroducing it.
package splice.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokenCostTest {

    private val cost = TokenCost()

    private val deepSeekOffPeak = ModelRates(input = 0.15, cacheRead = 0.003, output = 0.60)

    // Peak is exactly 2x off-peak, which is the discount DeepSeek documents. Kept here so the
    // off-peak choice is a visible decision rather than a number someone has to trust.
    private val deepSeekPeak = ModelRates(input = 0.30, cacheRead = 0.006, output = 1.20)

    /** The 67 turns the operator's report was measured from. */
    private val measuredSession = TokenBuckets(input = 305_460, cacheRead = 6_911_360, output = 46_897)

    @Test
    fun `the measured deepseek session prices at the published off-peak flash card`() {
        val usd = cost.of(measuredSession, deepSeekOffPeak)
        //  305460 * 0.15   =  45819.0
        // 6911360 * 0.003  =  20734.08
        //   46897 * 0.60   =  28138.2
        //                     ---------
        //                      94691.28 / 1e6 = 0.09469128
        assertEquals(0.09469128, usd, 1e-9)
        // ...which is the 0.09 the report named, to the cent the statusline renders.
        assertEquals("0.09", String.format(java.util.Locale.ROOT, "%.2f", usd))
        // The Anthropic card the client applies is ~40x this one on the same tokens; if a future
        // edit ever moves the arithmetic toward that number, this fails by a mile, not by a cent.
        assertTrue(usd < 0.5, "the whole point is that this is nowhere near the Anthropic-priced 3.69")
    }

    @Test
    fun `peak is twice off-peak and is never silently averaged with it`() {
        val offPeak = cost.of(measuredSession, deepSeekOffPeak)
        val peak = cost.of(measuredSession, deepSeekPeak)
        assertEquals(0.18938256, peak, 1e-9)
        assertEquals(2.0, peak / offPeak, 1e-9)
    }

    @Test
    fun `the three buckets are separable so a swapped cache-read and cache-miss rate fails`() {
        // Each bucket priced ALONE. A swap of input/cacheRead changes every one of these numbers,
        // which is the failure mode the acceptance names.
        val perBucket = listOf(
            TokenBuckets(input = 1_000_000) to deepSeekOffPeak.input,
            TokenBuckets(cacheRead = 1_000_000) to deepSeekOffPeak.cacheRead,
            TokenBuckets(output = 1_000_000) to deepSeekOffPeak.output,
        )
        for ((buckets, rate) in perBucket) {
            assertEquals(
                rate,
                cost.of(buckets, deepSeekOffPeak),
                1e-9,
                "one million of one bucket prices at that bucket's own rate",
            )
        }
        // And on the REAL session, the parts price to the whole: no bucket is dropped, and none is
        // counted twice. Priced independently, then compared against the single-pass total.
        val inputOnly = cost.of(measuredSession.copy(cacheRead = 0, output = 0), deepSeekOffPeak)
        val readOnly = cost.of(measuredSession.copy(input = 0, output = 0), deepSeekOffPeak)
        val outOnly = cost.of(measuredSession.copy(input = 0, cacheRead = 0), deepSeekOffPeak)
        assertEquals(
            cost.of(measuredSession, deepSeekOffPeak),
            inputOnly + readOnly + outOnly,
            1e-9,
            "bucket contributions must sum to the one-pass total",
        )
    }

    @Test
    fun `cache-write bills at its own rate when declared and at the cache-miss rate when not`() {
        val write = TokenBuckets(cacheWrite = 1_000_000)
        val withoutBucket = ModelRates(input = 0.08, cacheRead = 0.008, output = 0.28)
        assertEquals(
            0.08,
            cost.of(write, withoutBucket),
            1e-9,
            "no declared cache-write bucket bills conservatively as a miss",
        )
        val withBucket = withoutBucket.copy(cacheWrite = 0.20)
        assertEquals(0.20, cost.of(write, withBucket), 1e-9, "a declared bucket bills at its own rate")
    }

    @Test
    fun `a head card wins over the provider model entry`() {
        val providerEntry = ModelRates(input = 0.08, cacheRead = 0.008, output = 0.28)
        val reseller = ModelRates(input = 0.30, cacheRead = 0.006, output = 1.20)
        val heads = HeadRates { id -> if (id == "deepseek-flash") reseller else null }
        val resolved = cost.ratesFor(heads, "deepseek-flash", providerEntry)
        assertEquals(
            reseller,
            resolved,
            "a head override that is silently ignored is the exact defect this order prevents",
        )
        assertEquals(0.18938256, cost.of(measuredSession, resolved!!), 1e-9)
    }

    @Test
    fun `without a head card the provider entry answers, and without either the answer is null`() {
        val providerEntry = ModelRates(input = 0.08, cacheRead = 0.008, output = 0.28)
        assertEquals(providerEntry, cost.ratesFor(null, "deepseek-flash", providerEntry))
        // A head whose card names a DIFFERENT model does not cover this one — the provider entry does.
        val otherModelOnly = HeadRates { id -> if (id == "deepseek-v4-pro") providerEntry else null }
        assertEquals(providerEntry, cost.ratesFor(otherModelOnly, "deepseek-flash", providerEntry))
        // No card anywhere: null, and the caller falls back to the client's total_cost_usd.
        assertNull(cost.ratesFor(null, "deepseek-flash", null))
        assertNull(cost.ratesFor(otherModelOnly, "deepseek-flash", null))
    }

    @Test
    fun `a zero-token session costs nothing and says so`() {
        val empty = TokenBuckets()
        assertTrue(empty.isEmpty)
        assertEquals(0.0, cost.of(empty, deepSeekOffPeak), 1e-12)
    }
}
