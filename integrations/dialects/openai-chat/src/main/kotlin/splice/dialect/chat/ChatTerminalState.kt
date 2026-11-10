// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: the L3 verdict owner — six
// producers and one consumer (the ranked provider-failure `when`) in one file, strictly better
// co-location than sharing a file with tool-index synthesis and token accounting.
// Failure-handling slice 3: an error chunk is read once, by the head's own reader (ChatReplies with the head's
// deviation), where the shared classifier read it until then.
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.failure.FailureCause
import splice.core.failure.FailureEvidence
import splice.core.failure.Moment
import splice.core.failure.Position
import splice.core.turn.TurnOutcome
import splice.dialect.chat.failure.ChatReplies
import splice.upstream.failure.FailedEvent
import splice.upstream.failure.OpenAiErrorEnvelope

/** The chat dialect's honesty state machine: the flags that decide whether a turn's terminal is an
 *  honest Failure instead of a clean Success, and the finish_reason classification that feeds them.
 *  [toolCalls] is read through for CX-01's latch and written through for the `tool_calls`
 *  finish_reason, since [ChatToolCalls] owns the buffers those two facts are derived from. [ctx] carries the head's
 *  reader, which reads an error chunk. */
internal class ChatTerminalState(private val toolCalls: ChatToolCalls, private val ctx: ChatTurnContext) {

    internal var finished = false
    internal var incomplete = false
    internal var contentFiltered = false

    /** The model ended its answer itself (finish_reason stop or a synonym): its finished message, empty or not, as
     *  the passthrough twin's endedByModel and the Responses twin's closed message item read it. */
    internal var endedByModel = false
        private set

    /** The first finish_reason this dialect reads as no finished answer. Every value is read by name: an unknown one
     *  used to fall through to a clean end_turn, so a generation the backend aborted (DeepSeek's
     *  insufficient_system_resource, vLLM's abort, OpenRouter's error) reached Claude Code as a complete answer. */
    private var unknownFinish: String? = null

    /** A finish_reason arrived and it is one this dialect knows: the backend said its last word about the answer.
     *  CX-01's tool-argument latch runs only here; with no known terminal the stream is the tear path, as in the
     *  Responses twin (a stream with no completed response is torn, not a corrupt tool call). */
    internal val settled: Boolean get() = finished && unknownFinish == null

    /** Whether the stream has said all it will: an error chunk, or a finish whose bill has arrived ([billed]). The
     *  translator stops reading there instead of waiting for the socket to close, as the Responses twin stops at its
     *  completed response. A finish with no bill yet keeps reading, since include_usage sends the bill after it. */
    internal fun ended(billed: Boolean): Boolean = failure != null || (finished && billed)

    /** The backend's error chunk as the head's reader read it, and the provider's own words for the log. */
    private var failure: FailureCause.Provider? = null
    private var failureMessage: String = ""

    // Whether the error chunk named a vendor code or kind and no status: such a code no table names is the unknown
    // splice re-sends once (resendsOnce).
    private var failureCoded = false

    // CX-08 (L3): OpenAI carries a model refusal in a DEDICATED `refusal` field on the streamed
    // delta and on the final message — never in `content`. Unread, the one text that explains the
    // turn was discarded: a refusal with no prose fell through to the pipeline's generic
    // "model returned no content (empty response) — retry" (locally synthesized, so the G20 health
    // split blamed splice for the backend's verdict, and the operator was told to retry a request
    // the backend will refuse identically), and a refusal that followed streamed reasoning ended as
    // a clean Success with the chain of thought promoted to the answer.
    internal val refusalBuf = StringBuilder()

    // WIRE-2/3/6: textBuf/thinkingBuf/toolIndexById exist for legitimate dedup/replay and must
    // stay unbounded on the normal path; this local safety valve (never provider-reported) only
    // trips far above any real response, cleanly failing the turn instead of a runaway upstream
    // growing them without limit.
    internal var runawayGuard: String? = null

    /** The upstream SSE chunk carrying an `error` object, as sent. V4-164: read by the same reader that reads the
     *  head's failed replies, so an overflow reaches Claude Code as the "prompt is too long" it compacts on and a rate
     *  limit as a rate limit, and an in-band error reads as the same kind its HTTP twin does. */
    internal fun onError(chunk: JsonObject) {
        val error = OpenAiErrorEnvelope.of(chunk)
        failure = ctx.replies.event(FailedEvent(ChatReplies.ERROR_CHUNK, chunk, ctx.owner))
        failureMessage = error.message.orEmpty().ifEmpty { "error" }
        // V4-167: llama-server writes its status as a numeric code; a vendor's string code or kind is its own.
        failureCoded = error.code.toIntOrNull() == null && (error.code.isNotBlank() || error.type.isNotBlank())
    }

    /** Whether the failure this state reports is the backend's own error with a vendor code no table names, the one
     *  splice re-sends once (ChatResendOnce, final-design.md section 8); a local guard outranks it in
     *  [providerFailure], so it never does. */
    internal fun resendsOnce(): Boolean =
        runawayGuard == null && toolCalls.toolArgsInvalid == null &&
            failure is FailureCause.Provider.Unrecognized && failureCoded

    /** Known values are accepted by name and every other value fails honestly (L3): a cut-off answer must never reach
     *  the client as a clean stop. */
    internal fun onFinish(reason: String) {
        finished = true
        when (reason) {
            // function_call is the legacy spelling of the same ending.
            "tool_calls", "function_call" -> toolCalls.hasToolUse = true
            // OpenAI standard is "length"; several OpenAI-compat vendors also emit "max_tokens", and Mistral
            // "model_length" when the model's own context ran out.
            "length", "max_tokens", "model_length" -> incomplete = true
            "content_filter" -> contentFiltered = true
            // OpenAI's stop, and the natural endings OpenAI-compatible servers write in their own words.
            "stop", "stop_sequence", "eos", "eos_token" -> endedByModel = true
            else -> if (unknownFinish == null) unknownFinish = reason
        }
    }

    /** The ranked provider-failure verdict, or null when the turn has none. */
    internal fun providerFailure(): TurnOutcome.Failure? {
        val runaway = runawayGuard
        val reported = failure
        return when {
            // splice's own size guard tripped on what the backend sent: a local verdict (G20).
            runaway != null -> failed(FailureCause.Provider.ToolTear(runaway = true), runaway)
            // CX-01: a terminated turn whose tool arguments are corrupt must not reach the client
            // as a Success — provider-reported (the backend produced the bytes), so it retries.
            toolCalls.toolArgsInvalid != null -> failed(
                FailureCause.Provider.ToolTear(runaway = false),
                "chat backend: ${toolCalls.toolArgsInvalid} in tool call; retry",
            )
            // V4-164: the reader's verdict, carried through — the passthrough dialect's rule
            // (PassthroughTerminalState: "an earlier draft hard-coded UPSTREAM_REPORTED here and threw
            // both verdicts away"). An error no table names is Unrecognized, the backend's own report.
            reported != null -> failed(reported, "chat backend: $failureMessage")
            // CX-08: the backend populated `refusal` — a censored generation whose STATED REASON is
            // the only honest verdict available. Ranked above the content_filter branch below
            // because it carries the model's own words instead of a generic phrase; it outranks
            // `finished` for the same reason content_filter does (the same frame sets both).
            // isNotBlank, not isNotEmpty: a buffer of only whitespace fragments must still read as
            // "no refusal" now that fragments are accepted verbatim (round-2 review). V4-81, the
            // responses dialect's sibling: a refusal reproduces exactly, so the leaf is permanent and
            // the two dialects answer the same question the same way.
            refusalBuf.isNotBlank() -> failed(
                read(
                    ChatReplies.REFUSAL_EVENT,
                    JsonObject(mapOf(ChatReplies.REFUSAL_EVENT to JsonPrimitive(refusalBuf.toString()))),
                ),
                "chat backend: model refused. $refusalBuf",
            )
            // finish_reason=content_filter is a CENSORED turn — a clean end_turn would let a
            // blocked generation masquerade as complete (honesty invariant); it outranks
            // `finished` (the same frame sets both). V4-81: the identical prompt is filtered identically.
            contentFiltered -> failed(
                read(ChatReplies.FILTER_EVENT, JsonObject(emptyMap())),
                "chat backend: generation stopped by content filter",
            )
            // A finish_reason that names no finished answer: the backend ended the generation for a reason of its
            // own, so the turn fails with that reason instead of grading the cut-off text a clean end_turn.
            unknownFinish != null -> failed(
                read(
                    ChatReplies.FINISH_EVENT,
                    JsonObject(mapOf(ChatReplies.FINISH_EVENT to JsonPrimitive(unknownFinish))),
                ),
                "chat backend: generation ended on finish_reason \"$unknownFinish\", not a finished answer; retry",
            )
            else -> null
        }
    }

    /** A refusal or a content filter, read by the head's reader as an error chunk is. */
    private fun read(type: String, payload: JsonObject): FailureCause.Provider =
        ctx.replies.event(FailedEvent(type, payload, ctx.owner))

    private fun failed(cause: FailureCause, message: String): TurnOutcome.Failure = TurnOutcome.Failure(
        cause = cause,
        at = Position(Moment.MID_CONTENT, streamBegan = true),
        evidence = FailureEvidence(message),
    )
}
