// NEW: failure-handling slice 3 (2026-10-26). The Chat Completions reader (final-design.md section 7): every head that
// speaks OpenAI's Chat API (claude-grok, claudeor, the bonsai heads, claude-glml53) reads a failed reply, an in-band
// error chunk, a content-filter finish and a refusal here, once. The status and code rows are OpenAI's own, shared
// with Responses (OpenAiReplies); a provider that answers differently declares it in its own module (GrokReplies,
// LocalRuntimeReplies), and the foreign-key rule runs before both (Replies).
package splice.dialect.chat.failure

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.util.JsonScalars
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiErrorEnvelope
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ReplyReader

/** Reads Chat's failures: an HTTP status with OpenAI's error body, a chunk carrying an `error` object
 *  ([ERROR_CHUNK]), finish_reason content_filter ([FILTER_EVENT]) and a message's `refusal` ([REFUSAL_EVENT]). */
public object ChatReplies : ReplyReader {
    /** A chunk carrying an `error` object; its payload is the chunk as sent. */
    public const val ERROR_CHUNK: String = "error"

    /** finish_reason content_filter: the provider's filter stopped the generation. */
    public const val FILTER_EVENT: String = "content_filter"

    /** A message's `refusal`; its payload carries the refusal's text under `refusal`. */
    public const val REFUSAL_EVENT: String = "refusal"

    override fun http(reply: FailedReply): FailureCause.Provider = OpenAiReplies.http(reply)

    override fun event(event: FailedEvent): FailureCause.Provider = when (event.type) {
        FILTER_EVENT -> FailureCause.Provider.Refused(RefusedBy.FILTER)
        REFUSAL_EVENT -> FailureCause.Provider.Refused(RefusedBy.MODEL, ProviderWordsFilter.of(refusal(event.payload)))
        else -> OpenAiReplies.inBand(OpenAiErrorEnvelope.of(event.payload), event.owner)
    }

    private fun refusal(payload: JsonObject): String? = JsonScalars.str(payload, REFUSAL_EVENT)
}
