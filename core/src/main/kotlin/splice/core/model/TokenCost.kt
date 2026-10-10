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
// A WRITE BILLS BY ITS TTL (Oct 10, 2026 review). Anthropic and Moonshot each publish two write prices,
// one for a cache held five minutes and a higher one for an hour, and the wire reports which TTL the
// tokens were written at (cache_creation.ephemeral_1h_input_tokens). Collapsing the two charged every
// hourly write at the five-minute rate: 100k hourly writes on Opus 5.5 billed 0.50 instead of 0.80, so a
// 0.60 budget stayed open on a turn that had already passed it. The card carries both prices and the
// bucket carries the hourly share, so each write bills at the rate its own duration earns.
//
// PEAK vs OFF-PEAK. DeepSeek publishes two cards for the same model: peak is exactly 2x off-peak in the
// hours it names. Averaging them would invent a third price DeepSeek does not charge, so since Oct 10,
// 2026 the shipped card is the off-peak one with its [PeakHours], and each turn is priced at the card
// for the hour it ran in ([ModelRates.at]). A card written in TOML carries no peak hours: it is one price.
//
// LONG-CONTEXT TIERS (V4-240). OpenAI and xAI publish a second, higher card for a request whose
// input is over a size, and bill EVERY token of that request at it: gpt-6-sol past 272K input tokens,
// grok-4.7 from 200k. So the tier is a property of ONE request, and a sum over several requests must be
// priced request by request, never summed first and priced once: ten 100k turns are not one 1M
// request. Every caller prices per perf row (TurnPrice, TeamsEconomics, SessionCost).
//
// RESOLUTION ORDER, and it is the whole point of the amendment: a head's own card wins over the
// provider model entry, which wins over nothing. Nothing is a real answer — it is the caller's
// signal to fall back to the client's own total_cost_usd, so a head that declares no rates renders
// byte-identically to today (NEVER-BELOW-STATUS-QUO). The case the override exists for is two heads
// on ONE provider billed differently: a different account tier, or a reseller markup.
package splice.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** USD per MILLION tokens for one model.
 *
 *  [cacheWrite] null means the vendor reports no separate cache-write bucket, so those tokens bill
 *  at [input] — the conservative side, since a cache write never costs less than a cache miss.
 *  [cacheWriteHourly] is the price of a write held for an HOUR, which Anthropic and Moonshot both
 *  publish above their five-minute one (Anthropic: 2x input against 1.25x; Moonshot K3: 6 against
 *  3). Null is a vendor that publishes one write price, and then every write bills at [cacheWrite].
 *  [longContext] null means one card at every request size, and [peak] null one card at every
 *  hour. The TOML spelling is [ModelRatesToml]'s, and it has no peak hours. */
@Serializable(with = ModelRatesToml::class)
public data class ModelRates(
    val input: Double,
    val cacheRead: Double,
    val output: Double,
    val cacheWrite: Double? = null,
    val longContext: LongContextRates? = null,
    val peak: PeakHours? = null,
    /** APPENDED LAST, for the reason [splice.core.turn.Usage.cacheWriteTokens] is: a card is written
     *  positionally in the pins and in AddProfileCatalog, so a field beside [cacheWrite] would
     *  re-read those literals as a different price. */
    val cacheWriteHourly: Double? = null,
) {
    /** The card a turn that ran at [atMs] is billed at: [peak]'s multiple of this one inside its hours, else this
     *  one. A turn with no known time is billed at this card. */
    public fun at(atMs: Long?): ModelRates {
        val hours = peak ?: return this
        if (atMs == null || !hours.covers(atMs)) return this
        val f = hours.factor
        return ModelRates(
            input = input * f,
            cacheRead = cacheRead * f,
            output = output * f,
            cacheWrite = cacheWrite?.times(f),
            longContext = longContext?.let {
                it.copy(
                    input = it.input * f,
                    cacheRead = it.cacheRead * f,
                    output = it.output * f,
                    cacheWrite = it.cacheWrite?.times(f),
                    cacheWriteHourly = it.cacheWriteHourly?.times(f),
                )
            },
            cacheWriteHourly = cacheWriteHourly?.times(f),
        )
    }
}

/** V4-240: the vendor's card for a request whose input is MORE than [overInputTokens] tokens,
 *  counting fresh input, cache reads and cache writes alike. It bills the whole request, output
 *  included, which is how both vendors that publish one word it. A vendor whose tier starts AT a
 *  size (xAI: "reaches 200k") is declared one below it. */
public data class LongContextRates(
    val overInputTokens: Long,
    val input: Double,
    val cacheRead: Double,
    val output: Double,
    val cacheWrite: Double? = null,
    /** The tier's own 1-hour write, read like [ModelRates.cacheWriteHourly]: null is one write price
     *  at this tier, so an hourly write bills at [cacheWrite]. */
    val cacheWriteHourly: Double? = null,
)

/** A rate card as TOML spells it: `rates = { input = 2.0, cache_read = 0.2, output = 10.0 }` on the
 *  model row. V4-240: the long-context tier is spelled FLAT on the same card, every key prefixed
 *  `long_context_`, because ktoml 0.7.1 does not read an inline table nested inside a model row's
 *  inline card (measured: MissingRequiredPropertyException on ModelRates, ApiRateCardsTest) and the
 *  sub-table spelling repeats a header per model, which the preflight refuses (AddModel.ratesLine). */
internal object ModelRatesToml : KSerializer<ModelRates> {
    override val descriptor: SerialDescriptor = TomlRates.serializer().descriptor

    override fun deserialize(decoder: Decoder): ModelRates =
        decoder.decodeSerializableValue(TomlRates.serializer()).rates()

    override fun serialize(encoder: Encoder, value: ModelRates) {
        val tier = value.longContext
        encoder.encodeSerializableValue(
            TomlRates.serializer(),
            TomlRates(
                input = value.input,
                cacheRead = value.cacheRead,
                output = value.output,
                cacheWrite = value.cacheWrite,
                cacheWriteHourly = value.cacheWriteHourly,
                tierOver = tier?.overInputTokens,
                tierInput = tier?.input,
                tierCacheRead = tier?.cacheRead,
                tierOutput = tier?.output,
                tierCacheWrite = tier?.cacheWrite,
                tierCacheWriteHourly = tier?.cacheWriteHourly,
            ),
        )
    }
}

@Serializable
internal data class TomlRates(
    val input: Double,
    @SerialName("cache_read") val cacheRead: Double,
    val output: Double,
    @SerialName("cache_write") val cacheWrite: Double? = null,
    @SerialName("cache_write_1h") val cacheWriteHourly: Double? = null,
    @SerialName("long_context_over_input_tokens") val tierOver: Long? = null,
    @SerialName("long_context_input") val tierInput: Double? = null,
    @SerialName("long_context_cache_read") val tierCacheRead: Double? = null,
    @SerialName("long_context_output") val tierOutput: Double? = null,
    @SerialName("long_context_cache_write") val tierCacheWrite: Double? = null,
    @SerialName("long_context_cache_write_1h") val tierCacheWriteHourly: Double? = null,
) {
    fun rates(): ModelRates = ModelRates(
        input = input,
        cacheRead = cacheRead,
        output = output,
        cacheWrite = cacheWrite,
        longContext = tier(),
        cacheWriteHourly = cacheWriteHourly,
    )

    /** None of the tier's keys is no tier. Some of them is a card that would price a long request at a
     *  half-declared tier, so it is refused here, where the config is read, naming what is missing. */
    private fun tier(): LongContextRates? {
        val keys = listOf(tierOver, tierInput, tierCacheRead, tierOutput, tierCacheWrite, tierCacheWriteHourly)
        if (keys.all { it == null }) return null
        return LongContextRates(
            overInputTokens = requireNotNull(tierOver) { INCOMPLETE_TIER },
            input = requireNotNull(tierInput) { INCOMPLETE_TIER },
            cacheRead = requireNotNull(tierCacheRead) { INCOMPLETE_TIER },
            output = requireNotNull(tierOutput) { INCOMPLETE_TIER },
            cacheWrite = tierCacheWrite,
            cacheWriteHourly = tierCacheWriteHourly,
        )
    }
}

private const val INCOMPLETE_TIER = "a rate card's long-context tier needs long_context_over_input_tokens, " +
    "long_context_input, long_context_cache_read and long_context_output together " +
    "(long_context_cache_write and long_context_cache_write_1h are optional)"

/** The token buckets one cost covers, in the names the head's perf rows already carry. */
public data class TokenBuckets(
    val input: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val output: Long = 0,
    /** The SHARE of [cacheWrite] the request held for an hour, never an addition to it: the wire reports one
     *  cache-creation total and its per-TTL parts, so the hourly part is read off the total the same way
     *  [cacheWrite] is read off the input. The rest of [cacheWrite] is the five-minute write. Zero on every
     *  dialect whose wire reports no TTL split, which bills exactly as it did before. */
    val cacheWriteHourly: Long = 0,
) {
    public val isEmpty: Boolean get() = input == 0L && cacheRead == 0L && cacheWrite == 0L && output == 0L

    /** Every input token the request sent, which is what a long-context tier is measured against. */
    public val requestInput: Long get() = input + cacheRead + cacheWrite
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

    /** USD for [requests] requests whose summed buckets are [buckets], at [rates]. Each is priced at the
     *  long-context tier when their MEAN request input is over the tier's threshold, which is exact for
     *  one request and for requests on one side of it. Pure arithmetic over numbers splice already
     *  measured: no clock, no network, no vendor call. */
    public fun of(buckets: TokenBuckets, rates: ModelRates, requests: Long = 1L): Double {
        val count = requests.coerceAtLeast(1L)
        val card = rates.longContext?.takeIf { buckets.requestInput > it.overInputTokens * count }?.let { tier ->
            ModelRates(
                input = tier.input,
                cacheRead = tier.cacheRead,
                output = tier.output,
                cacheWrite = tier.cacheWrite,
                cacheWriteHourly = tier.cacheWriteHourly,
            )
        } ?: rates
        // Each write bills at the rate for the TTL the usage reported it at. The hourly share is a part of
        // the write bucket, so the five-minute write is the remainder; a card with one write price charges
        // both at it, and a bucket with no reported split charges all of it at the five-minute price, which
        // is what every row written before the split means.
        val perWrite = card.cacheWrite ?: card.input
        // minOf, not coerceIn: a malformed row with a negative write bucket must not throw inside the
        // budget's admission path, and a share wider than its bucket cannot bill tokens the bucket lacks.
        val hourly = minOf(buckets.cacheWriteHourly, buckets.cacheWrite).coerceAtLeast(0L)
        val weighted = buckets.input * card.input +
            buckets.cacheRead * card.cacheRead +
            (buckets.cacheWrite - hourly) * perWrite +
            hourly * (card.cacheWriteHourly ?: perWrite) +
            buckets.output * card.output
        return weighted / TOKENS_PER_MILLION
    }
}

private const val TOKENS_PER_MILLION = 1_000_000.0
