// NEW: failure-handling slice 3 (2026-10-26). Moved from upstream/local/LlamaCppErrors, which rewrote a local
// runtime's message inside the shared classifier: what a model server running on this machine says differently from
// the Chat standard, which a local head's Replies reads before the standard does (final-design.md section 12).
//
// Every shape is quoted from llama.cpp's server, where the wording is fixed: format_error_response
// (tools/server/server-common.cpp) emits {code: <HTTP-equivalent int>, message, type}; the error result's own json
// (server-task.cpp:1495) adds n_prompt_tokens and n_ctx to exceed_context_size_error only; and "Loading model"
// is the 503 served while the weights load (server-http.cpp:258). Ollama's own "try pulling it first" is its
// answer for a model it does not hold yet (FailureRules.kt:69-75 before slice 3). Provenance over wording: the
// first two are read by their structured type, so a reworded message keeps its reading.
package splice.provider.openai.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.ProviderWordsFilter
import splice.core.util.JsonScalars
import splice.upstream.failure.ErrorMessage
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.FailedReply
import splice.upstream.failure.ProviderDeviation
import splice.upstream.failure.ReplyFacts

/**
 * Three answers a local model server gives that the Chat standard reads differently.
 *
 * unavailable_error is the server still loading its weights, which the standard reads as a plain overload: four
 * glml53 journal lines between Oct 20 and 26 say "Loading model". Read as an overload that is still LOADING, the
 * line tells the person the server is coming up rather than that the provider is busy.
 *
 * exceed_context_size_error is a context overflow the overflow wording could not see: its message says "exceeds the
 * available context" and its code spells exceed_context, so before V4-164 Claude Code never got the overflow it
 * compacts on and a conversation that only needed compacting ended instead. Its own fields name the window.
 *
 * A model the runtime does not hold answers "try pulling it first" until the person pulls or loads it, which outlasts
 * any retry curve.
 */
public object LocalRuntimeReplies : ProviderDeviation {
    override fun http(reply: FailedReply): FailureCause.Provider? =
        row(errorObject(reply.body), ReplyFacts.retryAfterLift(reply) ?: Lift.Unstated)

    override fun event(event: FailedEvent): FailureCause.Provider? =
        row(event.payload["error"] as? JsonObject, Lift.Unstated)

    private fun row(error: JsonObject?, lift: Lift): FailureCause.Provider? {
        val type = JsonScalars.str(error, "type").orEmpty()
        val message = error?.let(ErrorMessage::inObject)
        val said = ProviderWordsFilter.of(message)
        return when {
            type == RUNTIME_LOADING -> FailureCause.Provider.Overloaded(lift, loading = true, said)
            type == RUNTIME_OVERFLOW ->
                FailureCause.Provider.TooLong(JsonScalars.long(error, "n_ctx"), whileAnswering = false, said)
            message != null && PULL_FIRST.containsMatchIn(message) ->
                FailureCause.Provider.ModelMissing(ReplyFacts.modelNamed(message), said)
            else -> null
        }
    }

    private fun errorObject(raw: String): JsonObject? =
        JsonScalars.objectOrNull(bodies, raw)?.get("error") as? JsonObject
}

/** llama-server's type for the 503 it serves while the model loads. */
private const val RUNTIME_LOADING = "unavailable_error"

/** Its type for a prompt past the context it has. */
private const val RUNTIME_OVERFLOW = "exceed_context_size_error"

/** Ollama's answer for a model it does not hold yet. */
private val PULL_FIRST = Regex("try pulling it first", RegexOption.IGNORE_CASE)

/** A local runtime's bodies are read as leniently as any other provider's. */
private val bodies = Json {
    isLenient = true
    ignoreUnknownKeys = true
}
