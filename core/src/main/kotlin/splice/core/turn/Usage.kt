// Split out of TurnOutcome.kt (2026-10-13, the concentration ratchet): the token bill a round
// reports — its buckets, its cache write and where it came from. An outcome carries a Usage; the
// bill is its own idea, read by the perf row, the budget and the client payload alike. Declarations
// unchanged and same-package, so every caller keeps splice.core.turn.{Usage,UsageField,...}.
package splice.core.turn

import kotlinx.serialization.Serializable

/** Token buckets observed by the provider, distinct from the numeric defaults used on the client wire. */
@Serializable
public enum class UsageField { INPUT, OUTPUT, CACHED, CACHE_WRITE }

private val ALL_USAGE_FIELDS = UsageField.entries.toSet()

public data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    // Prompt-cache read: input_tokens_details.cached_tokens (Responses) / cache_read_input_tokens.
    // The whole point of prompt_cache_key — surfaced so the HUD and log can report the real hit rate.
    val cachedTokens: Long = 0,
    // output_tokens_details.reasoning_tokens (Responses). Drives reasoning-continuation fold
    // detection (the 518n-2 truncation fingerprint); NEVER part of the client usage payload.
    val reasoningTokens: Long = 0,
    // V4-85: prompt-cache WRITE — cache_creation_input_tokens, or the sum of Anthropic's per-TTL
    // `cache_creation` buckets, with the 1-hour share beside it ([CacheWrite]). Like [cachedTokens] the
    // total is a DISJOINT part of [inputTokens], not an addition to it, and it exists because a cache
    // write bills at its own premium rate: folded into inputTokens alone it was indistinguishable from
    // a cache MISS and billed as one. Empty on every dialect whose wire reports no such bucket
    // (ChatUsage) — those heads never write a cache.
    //
    // APPENDED LAST, deliberately: Usage is constructed positionally as Usage(19, 7, 5, 3) in the
    // code-mode and custom-call pins, so inserting it beside cachedTokens where it semantically
    // belongs would silently re-read those four literals as a different set of buckets. Those pins
    // pass four positional arguments, so pairing the write with its TTL share leaves them reading the
    // same four buckets they always did.
    val cacheWrite: CacheWrite = CacheWrite(),
    val origin: UsageOrigin = UsageOrigin(),
    /** Only these observed buckets may become retained billing counters. Explicit zeros remain observations. */
    val reported: Set<UsageField> = ALL_USAGE_FIELDS,
) {
    /** Every token this request wrote to the cache, at any TTL: the bucket the perf row's
     *  cache_write_tokens carries, and a disjoint part of [inputTokens]. */
    public val cacheWriteTokens: Long get() = cacheWrite.tokens

    /** The share of [cacheWriteTokens] held for an hour, which bills above a five-minute write. */
    public val cacheWriteHourlyTokens: Long get() = cacheWrite.hourlyTokens

    /** Earlier requests retain their existing read surface and billing shape. */
    public val absorbed: AbsorbedRounds get() = origin.history.absorbed

    /** Requests cut before their backend bill arrived. */
    public val cutRounds: Long get() = origin.history.cutRounds

    public val unrecordedOutputTokens: Long get() = outputTokens - origin.recordedOutputTokens

    /** Supersede a posted final request, or retain it for a value that owns no request. */
    public fun followedBy(latest: Usage): Usage = UsageRounds.followedBy(this, latest)

    /** This usage's final round as an absorbed round, for the merge that supersedes it. A round that
     *  reported no input of its own is none: its output stays in [outputTokens], billed with the final. */
    public val finalRound: AbsorbedRounds get() {
        if (UsageField.INPUT !in reported) return AbsorbedRounds()
        if (origin.history.request == UsageRequest.NONE) return AbsorbedRounds()
        return AbsorbedRounds(
            rounds = 1,
            inputTokens = inputTokens,
            cachedTokens = cachedTokens,
            cacheWriteTokens = cacheWrite.tokens,
            outputTokens = outputTokens - absorbed.outputTokens,
            cacheWriteHourlyTokens = cacheWrite.hourlyTokens,
        )
    }

    /** Sum two rounds' usage — reasoning-continuation folding accumulates across hidden rounds. */
    public operator fun plus(other: Usage): Usage = Usage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cacheWrite = cacheWrite + other.cacheWrite,
        origin = UsageOrigin(
            localStep = origin.localStep || other.origin.localStep,
            codeModeDiverged = origin.codeModeDiverged || other.origin.codeModeDiverged,
            recordedOutputTokens = origin.recordedOutputTokens + other.origin.recordedOutputTokens,
            clientContext = other.origin.clientContext ?: origin.clientContext,
            history = origin.history + other.origin.history,
        ),
        reported = reported + other.reported,
    )
}

/** What one request wrote to the prompt cache: [tokens] is the whole write bucket and [hourlyTokens] the
 *  share of it the backend held for an HOUR rather than five minutes (Anthropic's
 *  cache_creation.ephemeral_1h_input_tokens).
 *
 *  Oct 10, 2026 review: the two travel together because they are ONE fact about ONE bucket, and because
 *  a write's price depends on which of them it falls in — Anthropic charges an hourly write 2x input
 *  against the five-minute write's 1.25x, and Moonshot K3 6 against 3. Reporting only the total charged
 *  every hourly write the cheaper rate, so a budget stayed open on spending that had already passed it.
 *  [hourlyTokens] is a PART of [tokens], never an addition, and zero on every wire that reports no TTL. */
public data class CacheWrite(val tokens: Long = 0, val hourlyTokens: Long = 0) {
    public operator fun plus(other: CacheWrite): CacheWrite =
        CacheWrite(tokens + other.tokens, hourlyTokens + other.hourlyTokens)

    /** How this bucket spells itself on a perf row: the total under [totalKey] always, and the hourly share
     *  under [hourlyKey] only when there is one. An absent hourly key reads as "all of it at the five-minute
     *  rate", which is what every row written before the split means, so no stored row changes price. The
     *  keys are passed in because the key catalogue (splice.core.perf.PerfKeys) belongs to the row, not to
     *  the bucket. */
    public fun counters(totalKey: String, hourlyKey: String): Map<String, Long> =
        if (hourlyTokens > 0) mapOf(totalKey to tokens, hourlyKey to hourlyTokens) else mapOf(totalKey to tokens)
}

/** Where a [Usage] came from beyond its token buckets: whether it is a local code-mode step, the
 *  requests folded into it, and the output and context that ride beside the bill. */
public data class UsageOrigin(
    /** A client-facing code-mode step synthesized locally; retained through round usage folding. */
    val localStep: Boolean = false,
    /** A code-mode branch changed a result already accepted on this conversation key. */
    val codeModeDiverged: Boolean = false,
    /** Output already recorded by an independently owned raw round, not a client-turn stamp. */
    val recordedOutputTokens: Long = 0,
    /** The context the client is told about when this usage measured no input of its own: a code-mode
     *  step with no upstream round carries its conversation's last measured round here, because Claude
     *  Code reads every assistant message's usage as the context total. Only the client payload reads
     *  it, and only while the usage's inputTokens is zero; splice's own accounting keeps the raw buckets. */
    val clientContext: Usage? = null,
    /** Requests billed before the final one, missing bills, and whether this value owns a final request. */
    val history: UsageHistory = UsageHistory(request = if (localStep) UsageRequest.NONE else UsageRequest.POSTED),
)
