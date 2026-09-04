// NEW: mid-stream re-anchoring (eli design 2026-07-24) — the proxy-side answer to codex-rs's
// whole-stream retry (responses_retry.rs). A round that fails RETRYABLY after frames were already
// forwarded is not surfaced to the client: the wire sits at a clean block boundary (the translator
// closeAlls before its terminal decision), so the turn re-POSTs a continuation carrying the
// accumulated partial output and APPENDS the remainder as new blocks. Replaying sent frames is
// impossible for a proxy (codex-rs overwrites its own terminal render; splice cannot un-send
// bytes) — but append needs no replay, and Claude Code commits nothing before message_stop.
// A retryable round with NOTHING replayable re-POSTs the ORIGINAL request verbatim instead —
// the whole-stream half of codex-rs's retry, which the marker continuation cannot cover.
// One honest terminal ends the whole turn (L3); tool rounds and an exhausted budget still
// fall back to the error.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.spi.ReanchorController
import splice.spi.ReanchorRound

public class ResponsesReanchorController(
    private val decodeReasoningEnvelope: ReasoningEnvelopeDecoder,
    private val maxContinuations: Int = DEFAULT_MAX_CONTINUATIONS,
) : ReanchorController {

    private val continuation = ResponsesContinuation()

    override fun continuationForFailure(round: ReanchorRound): JsonObject? {
        val partial = round.failure.partial ?: return null
        if (!eligible(round, partial)) return null
        // No salvage: re-POST the ORIGINAL request verbatim, the whole-stream retry codex-rs
        // performs (responses_retry.rs). Emitted thinking may precede this — the client's
        // partial thinking block is already closed (closeAll before the terminal decision) and
        // the fresh round appends new blocks, which is wire-legal. Supersedes the 2026-07-24
        // surface-honestly ruling for this case (operator call, 2026-08-26): that ruling
        // rejected an incoherent MARKER continuation mid-reasoning, not a clean restart.
        return if (!hasClientVisibleSalvage(partial)) round.requestBody else markerContinuation(round, partial)
    }

    // Tool blocks end eligibility both ways: an OPEN tear committed partial args JSON to the
    // wire (corrupt block, nothing to splice onto), and a COMMITTED tool_use means the
    // continuation input would carry a function_call without its function_call_output (a 400)
    // while a re-emitted call risks double-dispatch — and a verbatim restart would re-run the
    // dispatched call. Tool rounds fall back to the honest error either way.
    private fun eligible(round: ReanchorRound, partial: TurnOutcome.PartialRound): Boolean = when {
        round.attempt >= maxContinuations -> false
        round.failure.type !in RETRYABLE -> false
        partial.toolTearOpen || partial.hasToolUse -> false
        else -> true
    }

    /** Client-visible salvage = content the marker continuation can safely REPLAY: prose (rides as
     *  an assistant item) or encrypted reasoning envelopes. FoldRounds blanks buffered bodyText
     *  because that prose never reached the client; treating it as salvage would resume after a
     *  missing prefix. thinkingText alone likewise cannot seed a resume (code-review 2026-07-24),
     *  so those partials take the verbatim whole-request restart instead. */
    private fun hasClientVisibleSalvage(partial: TurnOutcome.PartialRound): Boolean {
        if (partial.bodyText.isNotEmpty()) return true
        return partial.reasoningEnvelopes.isNotEmpty()
    }

    private fun markerContinuation(round: ReanchorRound, partial: TurnOutcome.PartialRound): JsonObject {
        val items = buildList {
            partial.reasoningEnvelopes.mapNotNullTo(this) { decodeReasoningEnvelope(it) }
            if (partial.bodyText.isNotEmpty()) add(assistantText(partial.bodyText))
            add(reanchorMarker())
        }
        return continuation.continuationRequest(round.requestBody, items)
    }

    /** The partial prose the client already saw, replayed as context so the model resumes it. */
    private fun assistantText(text: String): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", text)
    }

    // phase:commentary keeps the marker out of the visible transcript (the fold marker trick) and
    // satisfies the Responses "reasoning item needs a following item" constraint.
    private fun reanchorMarker(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("phase", "commentary")
        put("content", MARKER_TEXT)
    }
}

// The re-anchor defaults, at file scope because Kotlin main sources carry no `companion` blocks.
// Narrowed from the companion's `public` to `private`: grep shows no consumer outside this file,
// in :app, or in any test, and a bare top-level `MARKER_TEXT` would otherwise be importable
// package-wide.
//
// PARITY WITH THE REFERENCE, raised from 2 (operator call, 2026-09-02). codex-rs retries a
// retryable stream DEFAULT_STREAM_MAX_RETRIES = 5 times (model-provider-info/src/lib.rs:27) before
// it switches transport, and this controller exists to be the proxy-side answer to exactly that
// loop. Two was chosen freehand and is simply too few: SSE is a FALLBACK, not a co-equal second
// transport, and every drop to it discards the socket's cache key and re-uploads the whole body,
// so the expensive path must not be reached until the cheap one has genuinely been exhausted. The
// argument that 2 was harmless because the live log never shows `re-anchor 2` measured the wrong
// thing — it says the first retry usually works, not that abandoning after the second is right,
// and it counts only rounds that were re-anchor ELIGIBLE at all. Turn recovery and its cooldown
// backoff are unchanged; this widens only how many times they may run.
private const val DEFAULT_MAX_CONTINUATIONS: Int = 5

private const val MARKER_TEXT: String =
    "Your previous stream was interrupted mid-answer. Continue EXACTLY where the text " +
        "above stops — do not repeat or restate anything already written, and do not " +
        "restate reasoning you have already given."

// FILE SCOPE ON PURPOSE: one shared immutable set, read per failure classification.
private val RETRYABLE = setOf(ErrorType.OVERLOADED, ErrorType.API_ERROR)
