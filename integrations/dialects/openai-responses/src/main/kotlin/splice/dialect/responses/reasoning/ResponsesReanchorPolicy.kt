// NEW: mid-stream re-anchoring (eli design 2026-07-24) — the proxy-side answer to codex-rs's
// whole-stream retry (responses_retry.rs). A round that fails RETRYABLY after frames were already
// forwarded is not surfaced to the client: the wire sits at a clean block boundary (the translator
// closeAlls before its terminal decision), so the turn re-POSTs a continuation carrying the
// accumulated partial output and APPENDS the remainder as new blocks. Replaying sent frames is
// impossible for a proxy (codex-rs overwrites its own terminal render; splice cannot un-send
// bytes) — but append needs no replay, and Claude Code commits nothing before message_stop.
// A retryable round with NOTHING replayable re-POSTs the ORIGINAL request verbatim instead —
// the whole-stream half of codex-rs's retry, which the marker continuation cannot cover.
// One honest terminal ends the whole turn (L3); tool rounds still fall back to the error. Which failures are carried
// on, and how many times, is RetryEngine's answer on the turn's one ledger (failure-handling slice 4): this rule only
// says whether the round can be carried on from what arrived, and builds the request that does.
//
// V4-339: the partial prose rides as [ResponsesAssistantText]'s item with the turn's [prosePhase]. It
// was said mid-turn, so a lite turn replays it as commentary, as its builder does every mid-turn
// preface (V4-335); replayed bare, it read to the model as the turn's final answer.
package splice.dialect.responses.reasoning

import kotlinx.serialization.json.JsonObject
import splice.core.turn.TurnOutcome
import splice.core.util.JsonScalars
import splice.dialect.responses.request.AssistantPhase
import splice.dialect.responses.request.ResponsesAssistantText
import splice.dialect.responses.stream.ResponsesContinuation
import splice.upstream.LiveRound
import splice.upstream.ReanchorPolicy
import splice.upstream.ReanchorRound

/** [prosePhase] is the phase the replayed partial prose carries. Public Responses turns use commentary
 *  unless native history supplies its known original phase. Null is a provider-owned exception. [continueReasoningOnly]
 *  is the vendor's quirk of the same name: a round that completed with reasoning alone is carried on from that
 *  reasoning instead of re-sent identically. */
internal class ResponsesReanchorPolicy(
    private val decodeReasoningEnvelope: ReasoningEnvelopeDecoder,
    private val prosePhase: AssistantPhase? = AssistantPhase.COMMENTARY,
    private val continueReasoningOnly: Boolean = false,
) : ReanchorPolicy {

    private val continuation = ResponsesContinuation()

    override fun continuationForFailure(round: ReanchorRound): JsonObject? {
        val partial = round.failure.partial ?: return null
        if (!continuable(partial)) return null
        // No salvage: re-POST the ORIGINAL request verbatim, the whole-stream retry codex-rs
        // performs (responses_retry.rs). Emitted thinking may precede this — the client's
        // partial thinking block is already closed (closeAll before the terminal decision) and
        // the fresh round appends new blocks, which is wire-legal. Supersedes the 2026-07-24
        // surface-honestly ruling for this case (operator call, 2026-08-26): that ruling
        // rejected an incoherent MARKER continuation mid-reasoning, not a clean restart.
        return if (!hasClientVisibleSalvage(partial)) round.requestBody else markerContinuation(round, partial)
    }

    /** The same refusal as [continuationForFailure], read off a round still streaming: a tool call has opened. Prose
     *  never ends it: with none the request restarts whole, with some it carries on. */
    override fun wouldContinue(live: LiveRound): Boolean = !live.toolOpened

    /** The round's encrypted reasoning, then a commentary marker asking for the answer it never gave: the same
     *  shape as [markerContinuation], which this vendor already accepts mid-stream. Null with the quirk off or no
     *  reasoning to carry, which keeps the identical re-send. */
    override fun continuationForEmpty(requestBody: JsonObject, empty: TurnOutcome.Success): JsonObject? {
        if (!continueReasoningOnly) return null
        val reasoning = empty.handoffs.reasoningEnvelopes.mapNotNull { decodeReasoningEnvelope(it) }
        if (reasoning.isEmpty()) return null
        val marker = ResponsesAssistantText.item(ANSWER_MARKER_TEXT, AssistantPhase.COMMENTARY)
        return continuation.continuationRequest(requestBody, reasoning + marker)
    }

    // Tool blocks end eligibility both ways: an OPEN tear committed partial args JSON to the
    // wire (corrupt block, nothing to splice onto), and a COMMITTED tool_use means the
    // continuation input would carry a function_call without its function_call_output (a 400)
    // while a re-emitted call risks double-dispatch — and a verbatim restart would re-run the
    // dispatched call. Tool rounds fall back to the honest error either way.
    private fun continuable(partial: TurnOutcome.PartialRound): Boolean = !partial.toolTearOpen && !partial.hasToolUse

    /** Client-visible salvage = content the marker continuation can safely REPLAY: prose (rides as
     *  an assistant item) or encrypted reasoning envelopes. FoldRounds blanks buffered bodyText
     *  because that prose never reached the client; treating it as salvage would resume after a
     *  missing prefix. thinkingText alone likewise cannot seed a resume (code-review 2026-07-24),
     *  so those partials take the verbatim whole-request restart instead. */
    private fun hasClientVisibleSalvage(partial: TurnOutcome.PartialRound): Boolean {
        if (partial.text.bodyText.isNotEmpty()) return true
        return partial.reasoningEnvelopes.isNotEmpty()
    }

    private fun markerContinuation(round: ReanchorRound, partial: TurnOutcome.PartialRound): JsonObject? {
        val native = nativeContinuation(partial)
        val hasNative = prosePhase != null && partial.nativePrefix != null
        if (hasNative && native == null) return null
        val items = buildList {
            if (native != null) {
                addAll(native)
            } else {
                partial.reasoningEnvelopes.mapNotNullTo(this) { decodeReasoningEnvelope(it) }
                if (partial.text.bodyText.isNotEmpty()) add(assistantText(partial.text.bodyText))
            }
            add(reanchorMarker())
        }
        return continuation.continuationRequest(round.requestBody, items)
    }

    private fun nativeContinuation(partial: TurnOutcome.PartialRound): List<JsonObject>? {
        if (prosePhase == null) return null
        val prefix = partial.deliveredNativePrefix ?: partial.nativePrefix ?: return null
        val delivered = partial.text.bodyText
        var offset = 0
        val items = buildList {
            for (item in prefix) {
                if (JsonScalars.str(item, "type") == "message") {
                    val model = messageText(item)
                    val text = deliveredPrefix(model, delivered, offset)
                    offset += text.length
                    if (text.isNotEmpty()) add(assistantText(text, replayPhase(item)))
                    if (text.length != model.length) break
                } else {
                    add(item)
                }
            }
        }
        return items.takeIf { offset == delivered.length }
    }

    private fun deliveredPrefix(model: String, delivered: String, offset: Int): String {
        val limit = minOf(model.length, delivered.length - offset)
        var length = 0
        while (length < limit) {
            if (model[length] != delivered[offset + length]) break
            length++
        }
        return model.substring(0, length)
    }

    private fun messageText(item: JsonObject): String = ResponsesMessageText.text(item)

    /** The partial prose the client already saw, replayed as context so the model resumes it. */
    private fun assistantText(text: String, phase: AssistantPhase? = prosePhase): JsonObject =
        ResponsesAssistantText.item(text, phase)

    private fun replayPhase(item: JsonObject): AssistantPhase? = when (JsonScalars.str(item, "phase")) {
        "commentary" -> AssistantPhase.COMMENTARY
        "final_answer" -> AssistantPhase.FINAL_ANSWER
        else -> prosePhase
    }

    // phase:commentary keeps the marker out of the visible transcript (the fold marker trick) and
    // satisfies the Responses "reasoning item needs a following item" constraint.
    // V4-342: [ResponsesAssistantText]'s item, the one author of an assistant item; the same bytes.
    private fun reanchorMarker(): JsonObject = ResponsesAssistantText.item(MARKER_TEXT, AssistantPhase.COMMENTARY)
}

// The re-anchor defaults, at file scope because Kotlin main sources carry no `companion` blocks.
// Narrowed from the companion's `public` to `private`: grep shows no consumer outside this file,
// in :app, or in any test, and a bare top-level `MARKER_TEXT` would otherwise be importable
// package-wide.

private const val MARKER_TEXT: String =
    "Your previous stream was interrupted mid-answer. Continue EXACTLY where the text " +
        "above stops. Do not repeat or restate anything already written, and do not " +
        "restate reasoning you have already given."

private const val ANSWER_MARKER_TEXT: String =
    "You finished reasoning without giving an answer or calling a tool. Give your answer now, " +
        "or make the tool call your reasoning chose. Do not restate your reasoning."
