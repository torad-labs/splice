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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.turn.isWeakSummaryText
import splice.spi.ClassifiedFailure
import splice.spi.FailureSource
import splice.spi.StreamTranslator
import splice.spi.UpstreamFailureClassifier
import splice.spi.WatchdogFired
import splice.spi.WireSink
import java.io.IOException
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

// Mutable per-block cursor. A data class here documents it as pure per-block state; it is only
// ever stored/looked up by wire index, never compared by value.
private data class BlockState(val index: WireBlockIndex, var sawDelta: Boolean)

public class ResponsesStreamTranslator(private val ctx: StreamTurnContext) : StreamTranslator {

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        // Stream read errors surface via the terminal decision, never a crash; only a genuine
        // cancellation (no watchdog fire) is allowed to propagate.
        val reducer = ResponsesEventReducer(ctx)
        try {
            upstream.collect { evt -> reducer.onEvent(evt, sink) }
        } catch (e: CancellationException) {
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // upstream read error: fall through to the honest terminal decision
        } catch (ignored: SerializationException) {
            // malformed upstream frame: fall through to the honest terminal decision
        } catch (ignored: IllegalArgumentException) {
            // malformed value in a frame: fall through to the honest terminal decision
        }

        sink.closeAll()
        harvestFallback(reducer)
        return terminalOutcome(reducer)
    }

    private fun terminalOutcome(reducer: ResponsesEventReducer): TurnOutcome =
        reducer.upstreamFailure?.let { TurnOutcome.Failure(it.type, "ChatGPT backend: ${it.message}") }
            ?: if (reducer.finalResponse == null) noCompletionOutcome() else successOutcome(reducer)

    // A COMPLETED response wins over a late watchdog fire. The watchdog polls the whole
    // enclosing coroutine, which stays suspended on the socket-EOF read AFTER the terminal
    // response.completed frame was already parsed; a fire in that window must NOT discard a
    // fully-received turn (that would retry a successful compaction — the exact quota waste the
    // watchdog exists to prevent). So the stall/truncation verdicts only apply when there is no
    // completed response yet (this path).
    private fun noCompletionOutcome(): TurnOutcome {
        ctx.watchdogFired()?.let { return watchdogOutcome(it) }
        return if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "claudex: upstream stream ended without response.completed (truncated); retry",
            )
        }
    }

    private fun watchdogOutcome(fired: WatchdogFired): TurnOutcome {
        val why = when (fired) {
            is WatchdogFired.Idle ->
                "no completion within the ${ctx.streamIdleMsForMessage / MS_PER_S}s idle cap"
            is WatchdogFired.TotalCap ->
                "no completion within the ${ctx.upstreamTimeoutMsForMessage / MS_PER_S}s total cap"
        }
        return TurnOutcome.Failure(
            ErrorType.OVERLOADED,
            "claudex: upstream stream stalled ($why) — aborted; retry",
        )
    }

    private fun successOutcome(reducer: ResponsesEventReducer): TurnOutcome = TurnOutcome.Success(
        hasToolUse = reducer.hasToolUse,
        incomplete = reducer.incomplete,
        usage = Usage(reducer.inputTokens, reducer.outputTokens, reducer.cachedTokens),
        thinkingText = reducer.thinkingBuf.toString(),
        bodyText = reducer.textBuf.toString(),
        emittedText = reducer.emittedText,
    )

    private fun harvestFallback(reducer: ResponsesEventReducer) {
        val resp = reducer.finalResponse ?: return
        val harvested = harvestResponsesOutput(resp)
        if (shouldPreferHarvestedText(reducer.textBuf.toString(), harvested.text)) {
            reducer.textBuf = StringBuilder(harvested.text)
        }
        if (harvested.thinking.length > reducer.thinkingBuf.length) {
            reducer.thinkingBuf = StringBuilder(harvested.thinking)
        }
    }

    private companion object {
        const val MS_PER_S = 1000L
    }
}

/**
 * Folds the upstream SSE event stream into per-turn buffers/flags. One private handler per
 * upstream event family — the ported shape of stream.mjs's runStreamTurn; the translator drives
 * it and reads the accumulated state to render the terminal outcome.
 */
private class ResponsesEventReducer(private val ctx: StreamTurnContext) {

    val blocks = HashMap<String, BlockState>()
    var hasToolUse = false
    var emittedText = false
    var incomplete = false
    var thinkingBuf = StringBuilder()
    var textBuf = StringBuilder()
    var finalResponse: JsonObject? = null
    var upstreamFailure: ClassifiedFailure? = null
    var inputTokens = 0L
    var outputTokens = 0L
    var cachedTokens = 0L
    private var toolSynthCounter = 0

    suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        when (str(evt["type"])) {
            "response.completed", "response.done", "response.incomplete" -> onTerminal(evt)
            "response.failed", "response.error", "error" -> onFailure(evt)
            else -> onStreamEvent(evt, sink)
        }
    }

    private suspend fun onStreamEvent(evt: JsonObject, sink: WireSink) {
        when (str(evt["type"])) {
            "response.output_item.added" -> onItemAdded(evt, sink)
            "response.output_item.done" -> onItemDone(evt, sink)
            "response.reasoning_summary_part.added" -> onSummaryPartAdded(evt, sink)
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" ->
                onThinkingDelta(evt, sink)
            "response.output_text.delta" -> onTextDelta(evt, sink)
            "response.function_call_arguments.delta", "response.function_call_arguments.done" ->
                onArgs(evt, sink)
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
        val u = usageFrom(resp)
        if (u.inputTokens > 0) inputTokens = u.inputTokens
        if (u.outputTokens > 0) outputTokens = u.outputTokens
        if (u.cachedTokens > 0) cachedTokens = u.cachedTokens
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
        val oi = intOr(evt[OUTPUT_INDEX]) ?: intOr(item["index"]) ?: blocks.size
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
        val oi = intOr(evt[OUTPUT_INDEX]) ?: intOr(item?.get("index"))
        if (oi != null) {
            blocks[oi.toString()]?.let { sink.closeBlock(it.index) }
            blocks["reasoning:$oi"]?.let { sink.closeBlock(it.index) }
        }
        // Replay (gated): emit the encrypted reasoning IN POSITION — right after its summary
        // closes and before the tool_use it preceded — so the round-trip preserves cache order.
        if (item != null && shouldReplayReasoning(ctx, item)) {
            ctx.encodeReasoningEnvelope(item)?.let { sink.addRedactedThinking(it) }
        }
    }

    private suspend fun onSummaryPartAdded(evt: JsonObject, sink: WireSink) {
        // New summary part = new paragraph in the SAME thinking block (v24: closing per part
        // truncated multi-part summaries — protocol violation, deltas after content_block_stop).
        val key = "reasoning:${intOr(evt[OUTPUT_INDEX]) ?: 0}"
        val b = blocks[key]
        if (b != null && b.sawDelta) {
            thinkingBuf.append("\n\n")
            sink.thinkingDelta(b.index, "\n\n")
        }
    }

    private suspend fun onThinkingDelta(evt: JsonObject, sink: WireSink) {
        val delta = str(evt[DELTA])
        if (delta.isEmpty()) return
        val b = ensureThinkingBlock(evt, sink)
        b.sawDelta = true
        thinkingBuf.append(delta)
        sink.thinkingDelta(b.index, delta)
    }

    private suspend fun ensureThinkingBlock(evt: JsonObject, sink: WireSink): BlockState {
        val key = "reasoning:${intOr(evt[OUTPUT_INDEX]) ?: 0}"
        blocks[key]?.let { return it }
        // separate multiple reasoning ITEMS in the mirror buffer
        if (thinkingBuf.isNotEmpty() && !thinkingBuf.endsWith("\n")) thinkingBuf.append("\n\n")
        val idx = sink.openThinking()
        val state = BlockState(idx, sawDelta = false)
        blocks[key] = state
        return state
    }

    private suspend fun onTextDelta(evt: JsonObject, sink: WireSink) {
        val delta = str(evt[DELTA])
        if (delta.isEmpty()) return
        val key = (intOr(evt[OUTPUT_INDEX]) ?: 0).toString()
        val b = blocks[key] ?: BlockState(sink.openText(), sawDelta = false).also { blocks[key] = it }
        emittedText = true
        textBuf.append(delta)
        sink.textDelta(b.index, delta)
    }

    // tool args stream as input_json_delta on the SAME wire block index; the .done frame closes it.
    private suspend fun onArgs(evt: JsonObject, sink: WireSink) {
        val b = blocks[(intOr(evt[OUTPUT_INDEX]) ?: return).toString()] ?: return
        if (str(evt["type"]) == "response.function_call_arguments.done") {
            sink.closeBlock(b.index)
        } else {
            val delta = str(evt[DELTA])
            if (delta.isNotEmpty()) sink.inputJsonDelta(b.index, delta)
        }
    }

    private companion object {
        const val OUTPUT_INDEX = "output_index"
        const val DELTA = "delta"
    }
}

/** Gated encrypted-reasoning replay predicate — kept out of the handler so its condition stays flat. */
private fun shouldReplayReasoning(ctx: StreamTurnContext, item: JsonObject): Boolean =
    ctx.replayReasoning && !ctx.compact &&
        str(item["type"]) == "reasoning" && str(item["encrypted_content"]).isNotEmpty()

/**
 * The terminal object's text replaces the streamed buffer when the stream produced nothing, or
 * when it produced only weak "no model text returned" filler that the harvested text improves on.
 */
private fun shouldPreferHarvestedText(current: String, harvested: String): Boolean {
    if (harvested.isEmpty()) return false
    return current.isEmpty() || (isWeakSummaryText(current) && !isWeakSummaryText(harvested))
}

private fun str(el: JsonElement?): String =
    (el as? JsonPrimitive)?.content ?: ""

private fun intOr(el: JsonElement?): Int? =
    (el as? JsonPrimitive)?.content?.toIntOrNull()
