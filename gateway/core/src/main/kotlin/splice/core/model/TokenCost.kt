// NEW: V4-37 — what a session costs, from numbers splice ALREADY owns times rates the operator declares.
//
// WHY THIS EXISTS. splice never computed cost. StatuslineBars.costSegment read
// `cost.total_cost_usd` straight out of the blob Claude Code pipes to the status line, and Claude
// Code prices with an ANTHROPIC card because it believes it is talking to Anthropic. Measured on
// the live claude-deepseek-perf.jsonl, 67 turns (fresh input 305460, cache read 6911360, output
// 46897), the DeepSeek flash card gives 0.09-0.19 USD while the Anthropic Sonnet card gives 3.69 —
// the number the operator saw. About 20x, and it hits EVERY non-Anthropic head, because the rate
// card the client applies is a property of the client, not of the wire.
//
// THREE BUCKETS, SPELLED PER MILLION TOKENS, because that is how every vendor on this box bills:
// input cache-MISS, input cache-READ (the discounted replay of a prefix), and output. A fourth
// cache-WRITE bucket is carried where the dialect reports one — Anthropic-shaped wires do, and those
// tokens bill at a premium rather than at the cache-miss rate.
//
// PEAK vs OFF-PEAK. DeepSeek publishes two cards for the same model, and this schema carries ONE,
// deliberately: averaging them would invent a third price DeepSeek does not charge. Declare the card
// you want reported. The DeepSeek profile declares OFF-PEAK and keeps the peak numbers beside it in
// a comment; peak is exactly 2x off-peak, which is the discount DeepSeek documents.
//
// RESOLUTION ORDER, and it is the whole point of the amendment: a head's own card wins over the
// provider model entry, which wins over nothing. Nothing is a real answer — it is the caller's
// signal to fall back to the client's own total_cost_usd, so a head that declares no rates renders
// byte-identically to today (NEVER-BELOW-STATUS-QUO). The case the override exists for is two heads
// on ONE provider billed differently: a different account tier, or a reseller markup.
package splice.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** USD per MILLION tokens for one model.
 *
 *  [cacheWrite] null means the vendor reports no separate cache-write bucket, so those tokens bill
 *  at [input] — the conservative side, since a cache write never costs less than a cache miss. */
@Serializable
public data class ModelRates(
    val input: Double,
    @SerialName("cache_read") val cacheRead: Double,
    val output: Double,
    @SerialName("cache_write") val cacheWrite: Double? = null,
)

/** The token buckets one cost covers, in the names the head's perf rows already carry. */
public data class TokenBuckets(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
) {
    public val isEmpty: Boolean get() = input == 0L && cacheRead == 0L && cacheWrite == 0L && output == 0L
}

/** A head's OWN card for one model id — an account tier or a reseller markup. Absent (null) =
 *  the provider model entry's own rates. Named for the role, per the kt-no-lambda-seam law: an
 *  unnamed `(String) -> ModelRates?` seam cannot say which layer of the resolution it supplies. */
public fun interface HeadRates {
    public operator fun invoke(modelId: String): ModelRates?
}

public class TokenCost {
    /** HEAD OVERRIDE FIRST, then the provider model entry, then null.
     *
     *  null is not a failure: it is how a head with no declared rates says "I have no card", and
     *  every caller must then render exactly what it renders today. An override that is silently
     *  ignored is the defect this method exists to make impossible — hence the pinned test. */
    public fun ratesFor(headRates: HeadRates?, modelId: String, providerEntry: ModelRates?): ModelRates? =
        headRates?.invoke(modelId) ?: providerEntry

    /** USD for [buckets] at [rates]. Pure arithmetic over numbers splice already measured — no clock,
     *  no network, no vendor call. */
    public fun of(buckets: TokenBuckets, rates: ModelRates): Double {
        val perWrite = rates.cacheWrite ?: rates.input
        val weighted = buckets.input * rates.input +
            buckets.cacheRead * rates.cacheRead +
            buckets.cacheWrite * perWrite +
            buckets.output * rates.output
        return weighted / TOKENS_PER_MILLION
    }
}

private const val TOKENS_PER_MILLION = 1_000_000.0
