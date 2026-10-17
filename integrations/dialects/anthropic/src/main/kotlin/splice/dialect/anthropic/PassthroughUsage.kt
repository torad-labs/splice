// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: CX-18's usage alias
// reads, on their own type with the four buckets they fill and the disjoint-usage projection that
// reads them back, moved verbatim.
package splice.dialect.anthropic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.CacheWrite
import splice.core.turn.Usage
import splice.core.turn.UsageField
import splice.core.util.JsonScalars

/** The passthrough dialect's usage accounting: the four Anthropic token buckets, the CX-18 alias
 *  reads that fill them, and the disjoint-usage projection the outcome carries. */
internal class PassthroughUsage {

    private var inputTokens = 0L
    private var cacheRead = 0L
    private var cacheCreation = 0L
    private var cacheCreationHourly = 0L
    private var outputTokens = 0L
    private val reported = mutableSetOf<UsageField>()

    /** Anthropic usage is disjoint; re-add the cache buckets so HeadServer's cached-subtraction
     *  reproduces the correct disjoint numbers. cachedTokens carries the prompt-cache-read hit.
     *
     *  V4-85: cacheWriteTokens carries the cache-WRITE half back out again. It used to be folded
     *  into inputTokens and then dropped, which left a cache write indistinguishable from a cache
     *  MISS downstream — so it billed at the input rate and the declared cache_write rate was dead
     *  arithmetic. inputTokens stays INCLUSIVE of it (CX-18: the context-window percentage is
     *  `(input + cache_creation + cache_read) / window`, which this numerator has to keep feeding);
     *  the new field is a disjoint READ-OFF of that same total, not an addition to it. */
    internal fun toUsage(): Usage = Usage(
        inputTokens = inputTokens + cacheRead + cacheCreation,
        outputTokens = outputTokens,
        cachedTokens = cacheRead,
        cacheWrite = CacheWrite(cacheCreation, cacheCreationHourly),
        reported = reported.toSet(),
    )

    /** The backend's disjoint counts, before the outcome's inclusive input normalization. The 1-hour
     *  write appears only on a turn that wrote one, so the usual line is unchanged and the TTL split is
     *  readable on exactly the turns whose price depends on it. */
    internal fun describe(): String =
        "input_tokens=$inputTokens cache_read_input_tokens=$cacheRead " +
            "cache_creation_input_tokens=$cacheCreation " +
            (if (cacheCreationHourly > 0) "ephemeral_1h_input_tokens=$cacheCreationHourly " else "") +
            "output_tokens=$outputTokens"

    internal fun harvestUsage(u: JsonObject?) {
        u ?: return
        JsonScalars.firstLong(u, "input_tokens")?.let {
            inputTokens = it
            reported += setOf(UsageField.INPUT, UsageField.CACHED, UsageField.CACHE_WRITE)
        }
        JsonScalars.firstLong(u, "cache_read_input_tokens")?.let {
            cacheRead = it
            reported += UsageField.CACHED
        }
        cacheCreationTokens(u)?.let {
            cacheCreation = it
            cacheCreationHourly = hourlyCacheCreationTokens(u).coerceAtMost(it)
            reported += UsageField.CACHE_WRITE
        }
        JsonScalars.firstLong(u, "output_tokens")?.let {
            outputTokens = it
            reported += UsageField.OUTPUT
        }
    }

    /** CX-18: the flat total, else the sum of Anthropic's newer per-TTL `cache_creation` buckets.
     *  Flat wins so a backend sending both is not double-counted, and the sum (not a first-of read)
     *  is what the two TTL buckets mean. These tokens fold into inputTokens in [toUsage], so
     *  missing them understated the whole context-window percentage on cache-writing turns. */
    private fun cacheCreationTokens(u: JsonObject): Long? =
        JsonScalars.firstLong(u, "cache_creation_input_tokens")
            ?: (u["cache_creation"] as? JsonObject)?.let { nested ->
                // SCOPED to *_input_tokens, not every value in the object. Summing everything
                // picks up a future non-additive sibling — a `total`, a `ttl` in seconds — and
                // folds it into inputTokens and therefore used_percentage, i.e. premature
                // auto-compaction: the same class CX-18 exists to prevent, in the other
                // direction. Naming the two known TTL keys instead would miss a new
                // ephemeral_1d_input_tokens bucket, so the suffix is the right seam.
                val parts = nested.filterKeys { it.endsWith("_input_tokens") }
                    .values.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() }
                parts.sum().takeIf { parts.isNotEmpty() }
            }

    /** Oct 10, 2026 review: the part of the write the backend held for an HOUR, which bills above a
     *  five-minute write at both vendors that report the split (Anthropic 2x input against 1.25x,
     *  Moonshot K3 6 against 3). Read from the nested breakdown even when the flat total is present,
     *  since Anthropic sends both and only the breakdown says which TTL the tokens were written at;
     *  0 when there is no breakdown, which bills the write exactly as it did before.
     *
     *  The known key is NAMED here rather than matched by suffix, the opposite of the total above, and
     *  for the same reason: every TTL carries its own published price, so a future
     *  ephemeral_1d_input_tokens bucket must earn its own column and rate before it can be charged.
     *  Folding it in here would bill a day-long write at the hourly price, which is a guess. It still
     *  reaches the write total above, so it bills at the five-minute rate until its column lands —
     *  understated, never invented. */
    private fun hourlyCacheCreationTokens(u: JsonObject): Long {
        val nested = u["cache_creation"] as? JsonObject ?: return 0L
        return (nested[HOURLY_WRITE_KEY] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
    }
}

// why: Anthropic's own name for the 1-hour cache-creation bucket, the one TTL splice carries a price for.
private const val HOURLY_WRITE_KEY = "ephemeral_1h_input_tokens"
