// NEW: mid-stream re-anchoring for the Anthropic-passthrough dialect — the layer this dialect was
// missing. ResponsesReanchorController has done this for the Responses dialect since 2026-07-24;
// Provider.reanchorController defaults to null ("surface the failure, pre-reanchor behaviour") and
// passthrough never overrode it, so every head on THIS dialect — the OAuth heads, kimi, muse,
// deepseek — ended a truncated turn immediately.
//
// It was not that the retries were removed. It is that none of the three layers can SEE this
// failure: a stream that EOFs without message_stop is a 2xx whose handler RETURNS a Failure, so
// UpstreamRequest wraps it as RetryOutcome.Done and the connect-phase budget (maxRetries=4) never
// runs; nothing throws, so the G5 reissue budget never runs either. Measured 2026-09-16: three
// deepseek truncations inside one minute, attempts=1 on all three, at 196, 44 and 989 content
// frames already delivered, each at inflight 8–9 — this upstream truncates under concurrency.
//
// WHY APPEND AND NEVER REPLAY. A proxy cannot un-send bytes. The client has already seen those
// frames, so re-POSTing the original request would duplicate visible output. But the wire sits at a
// clean block boundary (the translator closeAlls before deciding the terminal), Claude Code commits
// nothing before message_stop, and new blocks after a closed one are wire-legal — so the
// continuation appends the remainder as fresh blocks and the client reads one coherent message.
//
// THE CONTINUATION IS AN ASSISTANT PREFILL, which is this dialect's native resume: a trailing
// assistant message is the documented way to make an Anthropic-shaped endpoint continue an answer
// rather than restart it, so unlike the Responses twin no instruction marker is needed and none is
// sent. Trailing whitespace is stripped because a final assistant message that ends in whitespace
// is rejected upstream.
//
// AND THE CONTINUATION DISABLES THINKING, which is the whole reason a thinking-enabled turn is
// recoverable at all. MEASURED against api.deepseek.com/anthropic (2026-09-16), same history with a
// prior assistant thinking block, same 1/2/3 prefill, asked to count to ten:
//   thinking {"type":"enabled"}  -> 200, answers 1..10. The prefill is IGNORED; the model restarts.
//   thinking {"type":"disabled"} -> 200, answers 4..10. The prefill is honored, AND the thinking
//                                   block already in the replayed history is accepted regardless.
//   thinking key ABSENT          -> 200, emits a thinking block and restarts. OMITTING IS NOT
//                                   DISABLING: this model reasons by default, so an absent key
//                                   behaves like "enabled" and a prefill appended there would
//                                   duplicate everything the client had already read.
// So the field is written EXPLICITLY on every prefill continuation rather than removed. This is a
// value splice authors, not one it inherits: the reasoning for this turn already happened and was
// already streamed to the client, and the continuation round exists only to append the remaining
// TEXT. Refusing instead — the first shape of this file — left the operator's own failure case (196,
// 44 and 989 frames of visible salvage) ending exactly as it did before, with every test green.
//
// AND WHETHER A PREFILL IS SENT AT ALL IS A PER-VENDOR FACT, carried by quirks.reanchorPrefill and
// defaulted OFF — because the same probe run against the other heads on this dialect found that the
// shape is not universal. Measured 2026-09-16: kimi continues from a prefill in every thinking mode;
// muse REFUSES it outright with 400 "assistant prefill is not supported by this server", before it
// even reads the thinking field. Sending it there would turn a retryable overloaded_error into an
// invalid_request_error, which Claude Code does not retry — worse than the honest error, not level
// with it. So an unmeasured upstream keeps today's ending, and a measured one earns the resume.
package splice.dialect.passthrough

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.ReanchorController
import splice.spi.ReanchorRound

public class PassthroughReanchorController(
    private val prefill: Boolean = false,
    private val maxContinuations: Int = DEFAULT_MAX_CONTINUATIONS,
) : ReanchorController {

    override fun continuationForFailure(round: ReanchorRound): JsonObject? {
        val partial = round.failure.partial ?: return null
        if (!eligible(round, partial)) return null
        // Nothing the client can SEE was salvaged (thinking only, or a tear before the first text
        // delta): the fresh round appends new blocks after the closed ones, so a verbatim re-POST
        // duplicates nothing, and the turn keeps whatever thinking setting it asked for. That is
        // the whole-stream half of the retry. Otherwise resume from the exact text the client has.
        val resume = partial.bodyText.trimEnd()
        return when {
            resume.isEmpty() -> round.requestBody
            // This upstream has not been MEASURED to continue from a prefill, so it does not get
            // one. Returning null here is exactly today's behaviour — the honest error — which is
            // the floor this row must never go below, and it is strictly better than sending a
            // shape a vendor refuses: muse answers that with a 400 invalid_request_error, and
            // Claude Code retries overloaded_error but never invalid_request.
            !prefill -> null
            else -> prefillContinuation(round.requestBody, resume)
        }
    }

    /** Tool rounds end eligibility BOTH ways, exactly as the Responses twin argues it: an OPEN tear
     *  already committed partial argument JSON to the wire (a corrupt block, nothing to splice
     *  onto), and a COMMITTED tool_use means the continuation would carry a tool_use whose
     *  tool_result cannot exist yet, while re-emitting the call risks double-dispatch of a tool the
     *  client may already be running. Both fall back to the honest error. */
    private fun eligible(round: ReanchorRound, partial: TurnOutcome.PartialRound): Boolean = when {
        round.attempt >= maxContinuations -> false
        round.failure.type !in RETRYABLE -> false
        partial.toolTearOpen || partial.hasToolUse -> false
        else -> true
    }

    /** The original request with the partial answer appended as a trailing assistant message and
     *  thinking turned off, so the model continues from the answer's exact end instead of re-planning
     *  it. The input body is never mutated — a controller that edits the request it was handed would
     *  corrupt the very retry it exists to serve. A body with no `messages` array is not ours to
     *  rewrite and rides back unchanged (a verbatim restart). */
    private fun prefillContinuation(body: JsonObject, resume: String): JsonObject {
        val messages = body[MESSAGES] as? JsonArray ?: return body
        val continued = buildJsonArray {
            messages.forEach { element -> add(element) }
            add(assistantPrefill(resume))
        }
        val merged: Map<String, JsonElement> =
            body.toMap() + (MESSAGES to continued) + (THINKING to THINKING_OFF)
        return JsonObject(merged)
    }

    private fun assistantPrefill(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", JsonPrimitive(text))
                    },
                )
            },
        )
    }
}

private const val THINKING_TYPE = "type"
private const val THINKING_DISABLED = "disabled"

// Written onto every prefill continuation. One shared immutable value: the probe above is the only
// reason this file can resume a reasoning turn at all, and a second spelling of it could drift.
private val THINKING_OFF: JsonObject = buildJsonObject { put(THINKING_TYPE, THINKING_DISABLED) }

// Parity with the Responses twin, which took this number from codex-rs's own
// DEFAULT_STREAM_MAX_RETRIES. One definition per dialect so a log line cannot quote a budget the
// controller is not enforcing.
private const val DEFAULT_MAX_CONTINUATIONS: Int = 5

// FILE SCOPE ON PURPOSE: one shared immutable set, read once per failure classification.
//
// V4-57 adds RATE_LIMIT. A limit met after the first frame arrives as 200 + an SSE
// rate_limit_error and the client only auto-retries on HTTP status 429, so refusing to continue
// left that turn with no automatic recovery at all. Continuing is enough ON ITS OWN here — the
// re-POST is answered by a provider that is still limiting with a genuine pre-stream 429 carrying
// Retry-After headers, the one place a pushback is machine-readable; the pre-stream path already
// owns that decision (V4-48's short wait, and an honest 429 the client can retry on past the
// ceiling). No pushback is invented at this layer, because the wire never carried one to it.
private val RETRYABLE = setOf(ErrorType.OVERLOADED, ErrorType.API_ERROR, ErrorType.RATE_LIMIT)
