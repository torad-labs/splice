// NEW: failure-handling slice 3 (2026-10-26). The Responses reader (final-design.md section 7): claudex, over HTTP or the
// websocket, and claude-muse read a failed reply, a response.failed or error event, an incomplete answer and a refusal
// part here, once. An HTTP status reads by OpenAI's rows (OpenAiReplies), with the Responses API's two of its own; a
// provider that answers differently declares it in its own module (CodexReplies, MuseReplies), and the foreign-key rule
// runs before both (Replies).
package splice.dialect.responses.failure

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.failure.FailureCause
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.util.JsonScalars
import splice.core.wire.HttpStatus
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.OpenAiError
import splice.upstream.failure.OpenAiErrorEnvelope
import splice.upstream.failure.OpenAiReplies
import splice.upstream.failure.ReplyReader

/** Reads the Responses API's failures: an HTTP status with OpenAI's error body, a `response.failed`, `response.error`
 *  or `error` event ([FAILED_EVENT]), a `response.incomplete` whose reason is no clean stop ([INCOMPLETE_EVENT]) and a
 *  refusal part ([REFUSAL_PART]). */
public object ResponsesReplies : ReplyReader {
    /** A `response.failed`, `response.error` or `error` event; its payload is the event as sent. */
    public const val FAILED_EVENT: String = "failed"

    /** A `response.incomplete` whose reason is no clean stop; its payload carries the reason under `reason`. */
    public const val INCOMPLETE_EVENT: String = "incomplete"

    /** A refusal part; its payload carries the refusal's text under `refusal`. */
    public const val REFUSAL_PART: String = "refusal"

    /**
     * Two rows before OpenAI's. A 4xx after the websocket closed the same body with 1009 (message too big) is that
     * refusal again: on Oct 4 all 42 such rounds drew HTTP 400 `{"detail":"Bad Request"}` over SSE, a body that names
     * nothing, so it reads as too long, which Claude Code compacts on; its words were about a request the provider could
     * not read, so it quotes none, and a provider that named its own overflow keeps its reading. Then a 4xx naming
     * encrypted_content is the encrypted-reasoning contract (Grok Build and the ChatGPT backend reject a replayed
     * reasoning item they cannot decrypt), which the stale-reasoning amend serves (ResponsesFailureAmend).
     */
    override fun http(reply: FailedReply): FailureCause.Provider {
        val standard = OpenAiReplies.http(reply)
        val clientError = reply.status in HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR
        val sizeRefusal = reply.bodyRefusedAsTooLarge && clientError && reply.status != HttpStatus.TOO_MANY_REQUESTS
        return when {
            sizeRefusal && standard !is FailureCause.Provider.TooLong ->
                FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = false)
            refusesStaleReasoning(reply.status, reply.body) ->
                FailureCause.Provider.Rejected(staleReasoning = true, standard.said)
            else -> standard
        }
    }

    /** Whether a reply refuses reasoning this conversation can no longer send: a 4xx naming encrypted_content. The one
     *  predicate this reading and the stale-reasoning amend both key off (ResponsesFailureAmend), so the amend can
     *  never miss a reply this reader names, which a narrower match on the amend side once did (review 2026-07-24). */
    public fun refusesStaleReasoning(status: Int, body: String): Boolean =
        status in HttpStatus.BAD_REQUEST until HttpStatus.INTERNAL_SERVER_ERROR &&
            body.contains(ENCRYPTED_CONTENT, ignoreCase = true)

    override fun event(event: FailedEvent): FailureCause.Provider = when (event.type) {
        INCOMPLETE_EVENT ->
            if (JsonScalars.str(event.payload, "reason") == CONTENT_FILTER) {
                FailureCause.Provider.Refused(RefusedBy.FILTER)
            } else {
                FailureCause.Provider.Unrecognized(clientError = false)
            }
        REFUSAL_PART -> FailureCause.Provider.Refused(
            RefusedBy.MODEL,
            ProviderWordsFilter.of(JsonScalars.str(event.payload, REFUSAL_PART)),
        )
        else -> OpenAiReplies.inBand(error(event.payload), event.owner)
    }

    /** The error a failed round carries: response.failed's `response.error`, an error event's `error`, or the event's
     *  own fields when it carries them flat. A proxy may give the error as a plain string (DR-109), which is then its
     *  message, with no code. Public since slice 3: a provider that deviates on a code reads the same error this
     *  reader does, from its own module (CodexReplies), so one event is never unwrapped two ways. */
    public fun error(payload: JsonObject): OpenAiError {
        val carried = (payload["response"] as? JsonObject)?.get("error") ?: payload["error"]
        return when {
            carried is JsonObject -> OpenAiErrorEnvelope.error(carried)
            carried is JsonPrimitive && carried.isString -> OpenAiError("", "", carried.content)
            else -> OpenAiErrorEnvelope.of(payload)
        }
    }
}

private const val ENCRYPTED_CONTENT = "encrypted_content"
private const val CONTENT_FILTER = "content_filter"
