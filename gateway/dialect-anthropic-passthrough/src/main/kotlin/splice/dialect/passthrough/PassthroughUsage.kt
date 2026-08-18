// PORT-OF: PassthroughStreamTranslator.kt @ 71a203c — invariants unchanged: CX-18's usage alias
// reads, on their own type with the four buckets they fill and the disjoint-usage projection that
// reads them back, moved verbatim.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.turn.Usage
import splice.core.util.JsonScalars

/** The passthrough dialect's usage accounting: the four Anthropic token buckets, the CX-18 alias
 *  reads that fill them, and the disjoint-usage projection the outcome carries. */
internal class PassthroughUsage {

    private var inputTokens = 0L
    private var cacheRead = 0L
    private var cacheCreation = 0L
    private var outputTokens = 0L

    /** Anthropic usage is disjoint; re-add the cache buckets so HeadServer's cached-subtraction
     *  reproduces the correct disjoint numbers. cachedTokens carries the prompt-cache-read hit. */
    internal fun toUsage(): Usage = Usage(
        inputTokens = inputTokens + cacheRead + cacheCreation,
        outputTokens = outputTokens,
        cachedTokens = cacheRead,
    )

    internal fun harvestUsage(u: JsonObject?) {
        u ?: return
        JsonScalars.firstLong(u, "input_tokens")?.let { inputTokens = it }
        JsonScalars.firstLong(u, "cache_read_input_tokens")?.let { cacheRead = it }
        cacheCreationTokens(u)?.let { cacheCreation = it }
        JsonScalars.firstLong(u, "output_tokens")?.let { outputTokens = it }
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
}
