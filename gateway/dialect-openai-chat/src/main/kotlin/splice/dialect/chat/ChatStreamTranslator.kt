// NEW: (no Node source): OpenAI Chat Completions SSE → Anthropic SSE via the shared WireSink.
// Chat streaming shape: each frame is {choices:[{delta:{content?, reasoning_content?,
// tool_calls?}, finish_reason?}], usage?}. Text opens lazily on first content delta; a
// reasoning_content field (DeepSeek-style) opens a thinking block; tool_calls stream by index
// (delta.tool_calls[i].function.arguments). Same honesty gates as Responses: no clean end on a
// failure, ClientAbandoned when the client is gone before any finish. finish_reason maps:
// tool_calls→hasToolUse, length→incomplete, stop→end_turn.
package splice.dialect.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.spi.StreamTranslator
import splice.spi.WatchdogFired
import splice.spi.WireSink
import java.io.IOException
import java.util.concurrent.CancellationException

public data class ChatTurnContext(
    val clientGone: () -> Boolean,
    val watchdogFired: () -> WatchdogFired?,
    val idleCapMs: Long,
    val totalCapMs: Long,
)

public class ChatStreamTranslator(private val ctx: ChatTurnContext) : StreamTranslator {

    private var textBlock: WireBlockIndex? = null
    private var thinkingBlock: WireBlockIndex? = null
    private val toolBlocks = HashMap<Int, WireBlockIndex>()
    private var hasToolUse = false
    private var emittedText = false
    private var incomplete = false
    private val textBuf = StringBuilder()
    private val thinkingBuf = StringBuilder()
    private var finished = false
    private var contentFiltered = false
    private var inputTokens = 0L
    private var outputTokens = 0L
    private var cachedTokens = 0L
    private var failure: String? = null

    // Index-less parallel tool_calls (Mistral-shape backends emit complete calls with no
    // `index`): each NEW id gets its own synthesized slot — defaulting to 0 folded every
    // parallel call into one corrupted block (ids/names dropped, arguments concatenated).
    private var nextSynthToolIndex = SYNTH_INDEX_BASE
    private val toolIndexById = HashMap<String, Int>()

    override suspend fun driveTurn(upstream: Flow<JsonObject>, sink: WireSink): TurnOutcome {
        try {
            upstream.collect { evt -> onEvent(evt, sink) }
        } catch (e: CancellationException) {
            // Only a watchdog fire may swallow cancellation; a real cancel propagates.
            if (ctx.watchdogFired() == null) throw e
        } catch (ignored: IOException) {
            // stream read error: surface via the honest terminal decision, never a crash
        } catch (ignored: SerializationException) {
            // malformed upstream frame: surface via the honest terminal decision
        } catch (ignored: IllegalArgumentException) {
            // malformed value in a frame: surface via the honest terminal decision
        }
        sink.closeAll()
        return terminalOutcome()
    }

    // A FINISHED turn wins over a late watchdog fire (same rule the Responses translator pins:
    // the poller watches the whole coroutine, which can sit on the socket-EOF read AFTER
    // finish_reason already arrived — discarding a delivered turn retries a successful
    // generation, the exact quota waste the watchdog exists to prevent).
    private fun terminalOutcome(): TurnOutcome = when {
        failure != null -> TurnOutcome.Failure(ErrorType.API_ERROR, "chat backend: $failure")
        // finish_reason=content_filter is a CENSORED turn — a clean end_turn would let a blocked
        // generation masquerade as complete (honesty invariant). Retry an api_error honestly.
        contentFiltered ->
            TurnOutcome.Failure(ErrorType.API_ERROR, "chat backend: generation stopped by content filter")
        finished -> successOutcome()
        ctx.watchdogFired() != null ->
            TurnOutcome.Failure(ErrorType.OVERLOADED, "chat: upstream stalled — aborted; retry")
        else -> unfinishedOutcome()
    }

    private fun unfinishedOutcome(): TurnOutcome =
        if (ctx.clientGone()) {
            TurnOutcome.ClientAbandoned
        } else {
            TurnOutcome.Failure(
                ErrorType.OVERLOADED,
                "chat: stream ended without a finish_reason (truncated); retry",
            )
        }

    private fun successOutcome(): TurnOutcome = TurnOutcome.Success(
        hasToolUse = hasToolUse,
        incomplete = incomplete,
        usage = Usage(inputTokens, outputTokens, cachedTokens),
        thinkingText = thinkingBuf.toString(),
        bodyText = textBuf.toString(),
        emittedText = emittedText,
    )

    private suspend fun onEvent(evt: JsonObject, sink: WireSink) {
        (evt["error"] as? JsonObject)?.let {
            failure = str(it["message"]).ifEmpty { "error" }
            return
        }
        usage(evt)
        val choice = (evt["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["delta"] as? JsonObject)?.let { applyDelta(it, sink) }
        // Non-stream / final-message shape: reasoning lands on `message` instead of `delta`.
        (choice["message"] as? JsonObject)?.let { applyFinalMessage(it, sink) }
        str(choice["finish_reason"]).takeIf { it.isNotEmpty() }?.let { onFinish(it) }
    }

    /** The final-message fold: only fills channels the streamed deltas left empty/unseen. */
    private suspend fun applyFinalMessage(msg: JsonObject, sink: WireSink) {
        reasoningDeltaText(msg)?.let { r ->
            if (!thinkingBuf.contains(r)) {
                val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
                thinkingBuf.append(r)
                sink.thinkingDelta(idx, r)
            }
        }
        str(msg["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            if (textBuf.isEmpty()) {
                val idx = textBlock ?: sink.openText().also { textBlock = it }
                emittedText = true
                textBuf.append(c)
                sink.textDelta(idx, c)
            }
        }
    }

    private suspend fun applyDelta(delta: JsonObject, sink: WireSink) {
        // Vendors disagree on the cleartext CoT field name:
        // DeepSeek/xAI chat → reasoning_content; some OpenRouter/vLLM → reasoning; a few → thinking.
        reasoningDeltaText(delta)?.let { r ->
            val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
            thinkingBuf.append(r)
            sink.thinkingDelta(idx, r)
        }
        str(delta["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val idx = textBlock ?: sink.openText().also { textBlock = it }
            emittedText = true
            textBuf.append(c)
            sink.textDelta(idx, c)
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { tc -> applyToolCall(tc as? JsonObject ?: return@forEach, sink) }
    }

    /** First non-empty cleartext reasoning field on a chat delta/message. */
    private fun reasoningDeltaText(obj: JsonObject): String? {
        for (key in REASONING_KEYS) {
            val v = str(obj[key])
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private suspend fun applyToolCall(tc: JsonObject, sink: WireSink) {
        val index = resolveToolIndex(tc)
        val fn = tc["function"] as? JsonObject
        val idx = toolBlocks[index] ?: run {
            val id = str(tc["id"]).ifEmpty { "toolu_$index" }
            val opened = sink.openTool(id, str(fn?.get("name")))
            toolBlocks[index] = opened
            hasToolUse = true
            opened
        }
        str(fn?.get("arguments")).takeIf { it.isNotEmpty() }?.let { sink.inputJsonDelta(idx, it) }
    }

    /** Explicit index wins (OpenAI streaming); otherwise each distinct id gets a synthesized
     *  slot, and an id-less index-less call gets a fresh slot per event (complete-call shape). */
    private fun resolveToolIndex(tc: JsonObject): Int {
        (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        val id = str(tc["id"])
        if (id.isEmpty()) return nextSynthToolIndex++
        return toolIndexById.getOrPut(id) { nextSynthToolIndex++ }
    }

    private fun onFinish(reason: String) {
        finished = true
        when (reason) {
            "tool_calls" -> hasToolUse = true
            "length" -> incomplete = true
            "content_filter" -> contentFiltered = true
            else -> Unit // stop / others -> end_turn
        }
    }

    private fun usage(evt: JsonObject) {
        val u = evt["usage"] as? JsonObject ?: return
        (u["prompt_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { inputTokens = it }
        (u["completion_tokens"] as? JsonPrimitive)?.content?.toLongOrNull()?.let { outputTokens = it }
        // Prompt-cache read tokens — surfaced so the HUD/cache-log see a real hit rate. Details
        // field first (OpenAI standard: prompt_tokens_details.cached_tokens), then flat `cached_tokens`,
        // then DeepSeek's `prompt_cache_hit_tokens`. RAW here: prompt_tokens already INCLUDES this
        // cached portion and HeadServer disjoints them, so subtracting here would double-subtract.
        val details = u["prompt_tokens_details"] as? JsonObject
        val cached = details?.let { num(it, "cached_tokens") }?.takeIf { it > 0 }
            ?: num(u, "cached_tokens", "prompt_cache_hit_tokens")
        if (cached > 0) cachedTokens = cached
    }

    private fun num(obj: JsonObject, vararg keys: String): Long =
        keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L

    // JsonNull IS a JsonPrimitive and its `.content` is the 4-char string "null"; treat an explicit
    // JSON null (which every OpenAI-compatible vendor sends for finish_reason/content/id on
    // non-final chunks) as absent — else "null" leaks into text/reasoning/ids and, worst, a null
    // finish_reason would trip `finished` and let a truncated stream masquerade as a clean end (L3).
    private fun str(el: JsonElement?): String =
        (el as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: ""

    private companion object {
        val REASONING_KEYS = listOf("reasoning_content", "reasoning", "thinking", "reasoning_text")

        // Synthesized tool slots live far above any real streamed index (OpenAI streams 0..n).
        const val SYNTH_INDEX_BASE = 1_000_000
    }
}
