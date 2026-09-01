// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: CX-18's usage alias reads,
// on their own type with the three buckets they fill, moved verbatim.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.turn.Usage
import splice.core.util.JsonScalars

/** The chat dialect's usage accounting: the three token buckets and the CX-18 alias chain that
 *  fills them from a real-world backend's usage object. */
internal class ChatUsage {

    internal var inputTokens = 0L
    internal var outputTokens = 0L
    internal var cachedTokens = 0L

    internal fun toUsage(): Usage = Usage(inputTokens, outputTokens, cachedTokens)

    internal fun usage(evt: JsonObject) {
        val u = evt["usage"] as? JsonObject ?: return
        // CX-18: OpenRouter's Responses-shaped routes and several OpenAI-compatible servers report
        // the two main buckets under the input_/output_ spelling; reading only prompt_/completion_
        // landed those turns at zero usage. Canonical spelling first — a backend emitting both is
        // read by the standard field.
        JsonScalars.firstLong(u, "prompt_tokens", "input_tokens")?.let { inputTokens = it }
        JsonScalars.firstLong(u, "completion_tokens", "output_tokens")?.let { outputTokens = it }
        // Prompt-cache read tokens — surfaced so the HUD/cache-log see a real hit rate. Details
        // field first (OpenAI standard: prompt_tokens_details.cached_tokens), then flat `cached_tokens`,
        // then DeepSeek's `prompt_cache_hit_tokens`. RAW here: prompt_tokens already INCLUDES this
        // cached portion and HeadServer disjoints them, so subtracting here would double-subtract.
        val details = u["prompt_tokens_details"] as? JsonObject
        val cached = JsonScalars.firstLong(details, "cached_tokens")?.takeIf { it > 0 }
            ?: JsonScalars.firstLong(u, "cached_tokens", "prompt_cache_hit_tokens") ?: 0L
        if (cached > 0) cachedTokens = cached
    }
}
