// PORT-OF: server/src/codex/stream.mjs runStreamTurn @ 4ca99f7, event-for-event — its comments
// are the spec; every rule below pins a shipped bug:
//   - tool_use opens EAGERLY on output_item.added; text/reasoning open LAZILY on first delta
//     (empty thinking widgets otherwise);
//   - reasoning summary PARTS join with "\n\n" into ONE thinking block; closing per part was
//     v24's truncation bug — blocks close only on output_item.done / the end sweep;
//   - *_text.done / *_part.done are IGNORED (fire per part);
//   - tool args stream as input_json_delta on the SAME wire block index;
//   - failure events are captured and the loop CONTINUES (the terminal decision happens after);
//   - replay (gated) emits redacted_thinking IN POSITION right after its item closes;
//   - harvest fallback merges the terminal object's text/thinking when deltas were sparse
//     (weak-text preference rules);
//   - honest failures: upstreamFailure -> classified; watchdog-fired -> overloaded; stream end
//     without response.completed -> ClientAbandoned if the client is gone, else truncated.
// RESPONSIBILITY SPLIT (pinned P2-MACH slot note): promote-to-text, empty-compact/empty-model
// honesty, mirror, and terminal emission live in the GATEWAY pipeline; buffers ride
// TurnOutcome.Success.
package splice.dialect.responses

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.spi.ClassifiedFailure
import splice.spi.FailureSource
import splice.spi.UpstreamFailureClassifier
import splice.spi.WatchdogFired
import splice.spi.WireSink
import java.util.concurrent.CancellationException

/** Per-turn inputs the machine needs beyond the event flow. */
public data class StreamTurnContext(
    val compact: Boolean,
    val replayReasoning: Boolean,
    /** Encodes a reasoning item into the redacted_thinking envelope (gateway supplies; the
     *  envelope codec is splice-reasoning v1 and lands with P3-MIR). */
    val encodeReasoningEnvelope: (JsonObject) -> String?,
    /** True when the downstream client connection is already gone (client-abort detection). */
    val clientGone: () -> Boolean,
    /** The watchdog's typed sentinel, read AFTER the loop ends. */
    val watchdogFired: () -> WatchdogFired?,
    val streamIdleMsForMessage: Long,
    val upstreamTimeoutMsForMessage: Long,
)

@Suppress("UseDataClass") // mutable per-block cursor, not a value
private class BlockState(val index: WireBlockIndex, var sawDelta: Boolean)

@Suppress(
    "TooManyFunctions", // one private handler per upstream event family — the ported shape
    "CyclomaticComplexMethod", // the event switch + terminal cascade IS the ported contract
    "ComplexCondition", // gating conditions are quoted verbatim from stream.mjs
    "StringLiteralDuplication", // upstream event names/fields are inherently repeated literals
)
public class ResponsesStreamTranslator(private val ctx: StreamTurnContext) {

    private val blocks = HashMap<String, BlockState>()
    private var hasToolUse = false
    private var emittedText = false
    private var incomplete = false
    private var thinkingBuf = StringBuilder()
    private var textBuf = StringBuilder()
    private var finalResponse: JsonObject? = null
    private var upstreamFailure: ClassifiedFailure? = null
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var toolSynthCounter = 0

    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ReturnCount")
    // stream read errors surface via the outcome, never a crash; the terminal cascade returns
    public suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream.collect { evt -> onEvent(evt, sink) }
        } catch (e: Exception) {
            if (e is CancellationException && ctx.watchdogFired() == null) throw e
            // watchdog-cancelled or read error: fall through to the honest terminal decision
        }

        sink.closeAll()
        harvestFallback()

        upstreamFailure?.let { failure ->
            return TurnOutcome.Failure(failure.type, "ChatGPT backend: ${failure.message}")
        }
        ctx.watchdogFired()?.let { fired ->
            val why = when (fired) {
                is WatchdogFired.Idle ->
                    "no completion within the ${ctx.streamIdleMsForMessage / MS_PER_S}s idle cap"
                is WatchdogFired.TotalCap ->
                    "no completion within the ${ctx.upstreamTimeoutMsForMessage / MS_PER_S}s total cap"
            }
            return TurnOutcome.Failure(
                splice.core.turn.ErrorType.OVERLOADED,
                "claudex: upstream stream stalled ($why) — aborted; retry",
            )
        }
        if (finalResponse == null) {
            if (ctx.clientGone()) return TurnOutcome.ClientAbandoned
            return TurnOutcome.Failure(
                splice.core.turn.ErrorType.OVERLOADED,
                "claudex: upstream stream ended without response.completed (truncated); retry",
            )
        }
        return TurnOutcome.Success(
            hasToolUse = hasToolUse,
            incomplete = incomplete,
            usage = Usage(inputTokens, outputTokens),
            thinkingText = thinkingBuf.toString(),
            bodyText = textBuf.toString(),
            emittedText = emittedText,
        )
    }

    private suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (str(evt["type"])) {
            "response.completed", "response.done", "response.incomplete" -> onTerminal(evt)
            "response.failed", "response.error", "error" -> onFailure(evt)
            "response.output_item.added" -> onItemAdded(evt, sink)
            "response.output_item.done" -> onItemDone(evt, sink)
            "response.reasoning_summary_part.added" -> onSummaryPartAdded(evt, sink)
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                onThinkingDelta(evt, sink)
            "response.output_text.delta" -> onTextDelta(evt, sink)
            "response.function_call_arguments.delta" -> onArgsDelta(evt, sink)
            "response.function_call_arguments.done" -> onArgsDone(evt, sink)
            // *_text.done / *_part.done fire PER PART — closing here was the v24 truncation bug
            else -> Unit
        }
    }

    private fun onTerminal(evt: JsonObject) {
        val resp = (evt["response"] as? JsonObject) ?: evt
        finalResponse = resp
        if (str(evt["type"]) == "response.incomplete" || str(resp["status"]) == "incomplete") {
            incomplete = true
        }
        val (input, output) = usageFrom(resp)
        if (input > 0) inputTokens = input
        if (output > 0) outputTokens = output
    }

    private fun onFailure(evt: JsonObject) {
        // v25 honesty: failure events were silently discarded and the turn finished as a
        // clean empty end-of-turn, corrupting the transcript. Capture; keep reading.
        val e = (evt["response"] as? JsonObject)?.get("error") as? JsonObject
            ?: evt["error"] as? JsonObject
            ?: evt
        val code = str(e["code"]).ifEmpty { str(e["type"]) }.ifEmpty { "upstream_failed" }
        val message = str(e["message"]).ifEmpty { "ChatGPT backend reported failure" }
        upstreamFailure = UpstreamFailureClassifier.classify(FailureSource.SSE, "$code $message")
    }

    private suspend fun onItemAdded(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject ?: return
        val oi = intOr(evt["output_index"]) ?: intOr(item["index"]) ?: blocks.size
        if (str(item["type"]) == "function_call") {
            val id = str(item["call_id"]).ifEmpty { str(item["id"]) }
                .ifEmpty { "toolu_synth_${toolSynthCounter++}_$oi" }
            val idx = sink.openTool(id = id, name = str(item["name"]))
            blocks[oi.toString()] = BlockState(idx, sawDelta = false)
            hasToolUse = true
        }
        // reasoning + message (text) blocks open lazily on their first delta —
        // avoids empty thinking widgets when a reasoning item carries no summary
    }

    private suspend fun onItemDone(evt: JsonObject, sink: WireSink) {
        val item = evt["item"] as? JsonObject
        val oi = intOr(evt["output_index"]) ?: intOr(item?.get("index"))
        if (oi != null) {
            blocks[oi.toString()]?.let { sink.closeBlock(it.index) }
            blocks["reasoning:$oi"]?.let { sink.closeBlock(it.index) }
        }
        // Replay (gated): emit the encrypted reasoning IN POSITION — right after its summary
        // closes and before the tool_use it preceded — so the round-trip preserves cache order.
        if (ctx.replayReasoning && !ctx.compact && item != null &&
            str(item["type"]) == "reasoning" && str(item["encrypted_content"]).isNotEmpty()
        ) {
            ctx.encodeReasoningEnvelope(item)?.let { sink.addRedactedThinking(it) }
        }
    }

    private suspend fun onSummaryPartAdded(evt: JsonObject, sink: WireSink) {
        // New summary part = new paragraph in the SAME thinking block (v24: closing per part
        // truncated multi-part summaries — protocol violation, deltas after content_block_stop).
        val key = "reasoning:${intOr(evt["output_index"]) ?: 0}"
        val b = blocks[key]
        if (b != null && b.sawDelta) {
            thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
    }

    private suspend fun onThinkingDelta(evt: JsonObject, sink: WireSink) {
        val delta = str(evt["delta"])
        if (delta.isEmpty()) return
        val b = ensureThinkingBlock(evt, sink)
        b.sawDelta = true
        thinkingBuf.append(delta)
        sink.thinkingDelta(b.index, delta)
    }

    private suspend fun ensureThinkingBlock(evt: JsonObject, sink: WireSink): BlockState {
        val key = "reasoning:${intOr(evt["output_index"]) ?: 0}"
        blocks[key]?.let { return it }
        // separate multiple reasoning ITEMS in the mirror buffer
        if (thinkingBuf.isNotEmpty() && !thinkingBuf.endsWith("\n")) thinkingBuf.append("\n\n")
        val idx = sink.openThinking()
        val state = BlockState(idx, sawDelta = false)
        blocks[key] = state
        return state
    }

    private suspend fun onTextDelta(evt: JsonObject, sink: WireSink) {
        val delta = str(evt["delta"])
        if (delta.isEmpty()) return
        val key = (intOr(evt["output_index"]) ?: 0).toString()
        val b = blocks[key] ?: BlockState(sink.openText(), sawDelta = false).also { blocks[key] = it }
        emittedText = true
        textBuf.append(delta)
        sink.textDelta(b.index, delta)
    }

    private suspend fun onArgsDelta(evt: JsonObject, sink: WireSink) {
        val delta = str(evt["delta"])
        if (delta.isEmpty()) return
        val b = blocks[(intOr(evt["output_index"]) ?: return).toString()] ?: return
        sink.inputJsonDelta(b.index, delta)
    }

    private suspend fun onArgsDone(evt: JsonObject, sink: WireSink) {
        val b = blocks[(intOr(evt["output_index"]) ?: return).toString()] ?: return
        sink.closeBlock(b.index)
    }

    private fun harvestFallback() {
        val resp = finalResponse ?: return
        val harvested = harvestResponsesOutput(resp)
        if (harvested.text.isNotEmpty() && textBuf.isEmpty()) {
            textBuf = StringBuilder(harvested.text)
        } else if (harvested.text.isNotEmpty() &&
            isWeakSummaryText(textBuf.toString()) && !isWeakSummaryText(harvested.text)
        ) {
            textBuf = StringBuilder(harvested.text)
        }
        if (harvested.thinking.length > thinkingBuf.length) {
            thinkingBuf = StringBuilder(harvested.thinking)
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}

private fun str(el: kotlinx.serialization.json.JsonElement?): String =
    (el as? JsonPrimitive)?.content ?: ""

private fun intOr(el: kotlinx.serialization.json.JsonElement?): Int? =
    (el as? JsonPrimitive)?.content?.toIntOrNull()
