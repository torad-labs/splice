// NEW: failure-handling slice 3 (2026-10-26). The stop reasons that end an Anthropic answer as a failure, read where
// the message_delta that carries one meets AnthropicReplies. They moved out of PassthroughTerminalState with the rest
// of the table, and out of AnthropicReplies itself because the reader and this small table are two responsibilities
// (the concentration ratchet, V4-88).
package splice.dialect.anthropic.failure

import kotlinx.serialization.json.JsonObject
import splice.core.failure.FailureCause
import splice.core.failure.Lift
import splice.core.failure.ProviderWords
import splice.core.failure.ProviderWordsFilter
import splice.core.failure.RefusedBy
import splice.core.util.JsonScalars

/** A refusal is the model's verdict and quotes the category and explanation it carries (trace fae124199450 on
 *  claude-splice), model_context_window_exceeded is an answer that outgrew the window, and pause_turn keeps today's
 *  overload, since Claude Code 2.1.310 does not continue a paused turn (slice 0b's record). */
internal object AnthropicStopReasons {
    /** The stop reasons that end an answer as a failure. Anthropic's other four (end_turn, stop_sequence, tool_use,
     *  max_tokens) are clean, and an unknown one keeps end_turn's reading, as it always has: an off-spec value on a
     *  working turn must not fail it. */
    val FAILED: Set<String> = setOf(STOP_REFUSAL, STOP_PAUSE_TURN, STOP_WINDOW_EXCEEDED)

    /** [payload] is the message_delta's own delta object: its `stop_reason` and the `stop_details` beside it. */
    fun read(payload: JsonObject): FailureCause.Provider = when (JsonScalars.str(payload, "stop_reason")) {
        STOP_REFUSAL -> FailureCause.Provider.Refused(RefusedBy.MODEL, said(payload["stop_details"] as? JsonObject))
        STOP_PAUSE_TURN -> FailureCause.Provider.Overloaded(Lift.Unstated, loading = false)
        STOP_WINDOW_EXCEEDED -> FailureCause.Provider.TooLong(limitTokens = null, whileAnswering = true)
        else -> FailureCause.Provider.Unrecognized(clientError = false)
    }

    /** What a line quotes of a refusal: the backend's explanation with the category it named, as the filter cuts them,
     *  or either one alone where the backend gave only one. */
    private fun said(details: JsonObject?): ProviderWords? {
        val category = detail(details, "category")
        val explained = ProviderWordsFilter.of(detail(details, "explanation"))?.text?.removeSuffix(".")
        val quote = when {
            explained == null -> category
            category == null -> explained
            else -> "$explained ($category)"
        }
        return ProviderWordsFilter.of(quote)
    }

    private fun detail(details: JsonObject?, key: String): String? =
        JsonScalars.strIfString(details?.get(key)).takeIf { it.isNotBlank() }
}

/** Anthropic's three stop reasons that are no clean end. */
private const val STOP_REFUSAL = "refusal"
private const val STOP_PAUSE_TURN = "pause_turn"
private const val STOP_WINDOW_EXCEEDED = "model_context_window_exceeded"
