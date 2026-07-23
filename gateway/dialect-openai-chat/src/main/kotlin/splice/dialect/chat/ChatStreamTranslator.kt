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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.index.WireBlockIndex
import splice.core.turn.ErrorType
import splice.core.turn.TurnOutcome
import splice.core.turn.Usage
import splice.core.util.strOrEmpty
import splice.spi.StreamTranslator
import splice.spi.TerminalStates
import splice.spi.WatchdogFired
import splice.spi.WireSink
import splice.spi.terminalPrecedence
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

    // Ids of tool blocks already opened this turn — lets the final-message fold tell an ECHO of a
    // streamed call (suppress) from a call present ONLY in the final consolidated array (emit).
    private val openedToolIds = HashSet<String>()

    // Deferred opens: backends often emit index+id first and function.name on a later delta.
    // Opening with name="" freezes an empty tool_use on the Anthropic wire — buffer until name
    // arrives (or finish_reason forces a flush).
    private val pendingTools = HashMap<Int, PendingTool>()

    private data class PendingTool(
        var id: String,
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

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
        flushPendingTools(sink)
        sink.closeAll()
        return terminalOutcome()
    }

    // Ordering enforced by the shared spi.terminalPrecedence (a FINISHED turn beats a late
    // watchdog fire — the poller can sit on the socket-EOF read AFTER finish_reason arrived).
    private fun terminalOutcome(): TurnOutcome {
        val providerFailure = when {
            failure != null ->
                TurnOutcome.Failure(ErrorType.API_ERROR, "chat backend: $failure", providerReported = true)
            // finish_reason=content_filter is a CENSORED turn — a clean end_turn would let a
            // blocked generation masquerade as complete (honesty invariant); it outranks
            // `finished` (the same frame sets both). Retry an api_error honestly.
            contentFiltered -> TurnOutcome.Failure(
                ErrorType.API_ERROR,
                "chat backend: generation stopped by content filter",
                providerReported = true, // finish_reason the backend sent, not a local verdict (G20)
            )
            else -> null
        }
        return terminalPrecedence(
            TerminalStates(
                providerFailure = providerFailure,
                finished = finished,
                watchdogFired = ctx.watchdogFired(),
            ),
            onFinished = ::successOutcome,
            onWatchdog = { TurnOutcome.Failure(ErrorType.OVERLOADED, "chat: upstream stalled — aborted; retry") },
            onUnfinished = {
                if (ctx.clientGone()) {
                    TurnOutcome.ClientAbandoned
                } else {
                    TurnOutcome.Failure(
                        ErrorType.OVERLOADED,
                        "chat: stream ended without a finish_reason (truncated); retry",
                    )
                }
            },
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
            failure = strOrEmpty(it["message"]).ifEmpty { "error" }
            return
        }
        usage(evt)
        val choice = (evt["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["delta"] as? JsonObject)?.let { applyDelta(it, sink) }
        // Non-stream / final-message shape: reasoning lands on `message` instead of `delta`.
        (choice["message"] as? JsonObject)?.let { applyFinalMessage(it, sink) }
        // A null finish_reason would trip `finished` and let a truncated stream masquerade as a
        // clean end (L3) — strOrEmpty (core JsonScalars) filters the JsonNull first (review 2026-07-22 round 3).
        strOrEmpty(choice["finish_reason"]).takeIf { it.isNotEmpty() }?.let { onFinish(it) }
    }

    /** The final-message fold: only fills channels the streamed deltas left empty/unseen. */
    private suspend fun applyFinalMessage(msg: JsonObject, sink: WireSink) {
        foldFinalProse(msg, sink)
        foldFinalToolCalls(msg, sink)
    }

    /** The reasoning + text halves of the final-message fold, both prefix-aware via
     *  [unseenSuffix] (extracted so applyFinalMessage stays under the complexity gate). */
    private suspend fun foldFinalProse(msg: JsonObject, sink: WireSink) {
        reasoningDeltaText(msg)?.let { r ->
            val toEmit = unseenSuffix(thinkingBuf.toString(), r)
            if (toEmit.isNotEmpty()) {
                val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
                thinkingBuf.append(toEmit)
                sink.thinkingDelta(idx, toEmit)
            }
        }
        strOrEmpty(msg["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val toEmit = unseenSuffix(textBuf.toString(), c)
            if (toEmit.isNotEmpty()) {
                val idx = textBlock ?: sink.openText().also { textBlock = it }
                emittedText = true
                textBuf.append(toEmit)
                sink.textDelta(idx, toEmit)
            }
        }
    }

    // Non-stream / final-message shape: tool_calls land on `message`, not `delta`. Classified PER
    // CALL, not per turn — a turn-global gap-fill flag dropped a call present only in the final
    // array when it rode alongside an echo of a streamed call (review 2026-07-23). Three cases:
    //   ECHO of an already-opened block (matched by id) — SUPPRESS: re-applying appends the full
    //     arguments onto the open block or mints a duplicate tool_use (final-shape calls carry no
    //     `index`, so resolveToolIndex cannot map an echo back to its streamed slot).
    //   PENDING slot buffered from deltas that never carried function.name — adopt the echo's name
    //     by id, else flushPendingTools opens it under the "tool" fallback. Take the echo's args
    //     too ONLY when the deltas buffered none (name AND args both final-only, finding 3); a
    //     non-empty buffer is never appended twice.
    //   NEW — never streamed, present ONLY in the final consolidated message (including when NO
    //     deltas streamed any tool call) — emit it, even when it carries no name (opened under the
    //     "tool" fallback, finding 5a), or it is silently lost while the turn reports tool_use.
    // KNOWN LIMITATIONS (non-standard vendors only — not codex/grok/kimi; a name+args suppressor
    // would risk dropping a legitimate distinct call, so both are left as documented gaps):
    //   • a call STREAMED without an id (synth "toolu_<n>" slot) but echoed WITH an id can't be
    //     matched back, so the echo mints a duplicate tool_use (finding 4);
    //   • an id-matched echo is suppressed wholesale, so if the stream UNDER-delivered a call's
    //     arguments the final's complete copy is discarded (finding 5b).
    private suspend fun foldFinalToolCalls(msg: JsonObject, sink: WireSink) {
        val calls = msg["tool_calls"] as? JsonArray ?: return
        calls.forEach { tc -> (tc as? JsonObject)?.let { applyFinalToolCall(it, sink) } }
    }

    private suspend fun applyFinalToolCall(obj: JsonObject, sink: WireSink) {
        val id = strOrEmpty(obj["id"])
        if (id.isNotEmpty() && id in openedToolIds) return // echo of an already-open block
        val fn = obj["function"] as? JsonObject
        val slot = if (id.isEmpty()) null else pendingTools.entries.firstOrNull { it.value.id == id }
        if (slot == null) {
            // A call present ONLY in the final array (never an open block — those returned at the
            // top) — emit it, even when it carries no name (openPendingTool falls back to "tool",
            // finding 5a), else it is silently lost while the turn reports tool_use.
            applyToolCall(obj, sink)
        } else {
            // Pending slot from deltas that never carried a name — adopt the echo's name by id, and
            // take its arguments too only when the deltas buffered none (name AND args both final-
            // only, finding 3); a non-empty buffer is never double-appended (append("") is a no-op).
            val pending = slot.value
            val name = strOrEmpty(fn?.get("name"))
            if (name.isNotEmpty() && pending.name.isEmpty()) {
                pending.name = name
                if (pending.args.isEmpty()) pending.args.append(strOrEmpty(fn?.get("arguments")))
                openPendingTool(slot.key, pending, sink)
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
        strOrEmpty(delta["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val idx = textBlock ?: sink.openText().also { textBlock = it }
            emittedText = true
            textBuf.append(c)
            sink.textDelta(idx, c)
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { tc -> applyToolCall(tc as? JsonObject ?: return@forEach, sink) }
    }

    private suspend fun applyToolCall(tc: JsonObject, sink: WireSink) {
        val index = resolveToolIndex(tc)
        val fn = tc["function"] as? JsonObject
        val name = strOrEmpty(fn?.get("name"))
        val idChunk = strOrEmpty(tc["id"])
        val args = strOrEmpty(fn?.get("arguments"))
        val opened = toolBlocks[index]
        if (opened != null) {
            if (args.isNotEmpty()) sink.inputJsonDelta(opened, args)
            return
        }
        val pending = pendingTools.getOrPut(index) {
            PendingTool(id = idChunk.ifEmpty { "toolu_$index" })
        }
        if (idChunk.isNotEmpty()) pending.id = idChunk
        if (name.isNotEmpty()) pending.name = name
        if (args.isNotEmpty()) pending.args.append(args)
        if (pending.name.isNotEmpty()) {
            openPendingTool(index, pending, sink)
        }
    }

    // Callers guarantee toolBlocks[index] is absent: applyToolCall early-returns on an open block
    // with no suspension before calling here, and flushPendingTools only iterates keys still in
    // pendingTools (removed below in the same uninterruptible span that fills toolBlocks).
    private suspend fun openPendingTool(index: Int, pending: PendingTool, sink: WireSink) {
        val opened = sink.openTool(pending.id, pending.name.ifEmpty { "tool" })
        toolBlocks[index] = opened
        openedToolIds.add(pending.id)
        hasToolUse = true
        pendingTools.remove(index)
        if (pending.args.isNotEmpty()) sink.inputJsonDelta(opened, pending.args.toString())
    }

    private suspend fun flushPendingTools(sink: WireSink) {
        if (pendingTools.isEmpty()) return
        // Snapshot keys — openPendingTool mutates pendingTools.
        pendingTools.keys.toList().forEach { index ->
            pendingTools[index]?.let { openPendingTool(index, it, sink) }
        }
    }

    /** Explicit index wins (OpenAI streaming); otherwise each distinct id gets a synthesized
     *  slot, and an id-less index-less call gets a fresh slot per event (complete-call shape). */
    private fun resolveToolIndex(tc: JsonObject): Int {
        (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull()?.let { return it }
        val id = strOrEmpty(tc["id"])
        if (id.isEmpty()) return nextSynthToolIndex++
        return toolIndexById.getOrPut(id) { nextSynthToolIndex++ }
    }

    private fun onFinish(reason: String) {
        finished = true
        when (reason) {
            "tool_calls" -> hasToolUse = true
            // OpenAI standard is "length"; several OpenAI-compat vendors also emit "max_tokens".
            "length", "max_tokens" -> incomplete = true
            "content_filter" -> contentFiltered = true
            else -> Unit // stop / others -> end_turn
        }
    }

    private fun usage(evt: JsonObject) {
        val u = evt["usage"] as? JsonObject ?: return
        fun num(obj: JsonObject, vararg keys: String): Long =
            keys.firstNotNullOfOrNull { (obj[it] as? JsonPrimitive)?.content?.toLongOrNull() } ?: 0L
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

    private companion object {
        // Synthesized tool slots live far above any real streamed index (OpenAI streams 0..n).
        const val SYNTH_INDEX_BASE = 1_000_000
    }
}

private val REASONING_KEYS = listOf("reasoning_content", "reasoning", "thinking", "reasoning_text")

/** First non-empty cleartext reasoning field on a chat delta/message. Top-level (off the class
 *  function budget) — reads only its argument. */
private fun reasoningDeltaText(obj: JsonObject): String? {
    for (key in REASONING_KEYS) {
        val v = strOrEmpty(obj[key])
        if (v.isNotEmpty()) return v
    }
    return null
}

/** Prefix-aware fold shared by the reasoning and text final-message channels: emit only what the
 *  streamed deltas haven't already carried ("Hello" streamed + "Hello world" final → " world";
 *  full duplicate → nothing; disjoint payload → all of it). A plain contains() check appended
 *  "Hello world" onto "Hello" → "HelloHello world". Known gap (left as-is): a final message that
 *  re-emits the streamed prose with NORMALIZED whitespace is neither a clean prefix nor a
 *  substring, so it falls to the `else` and re-sends in full — a whitespace-aware compare can't be
 *  made provably safe (it would drop legitimately-repeated content). */
private fun unseenSuffix(already: String, final: String): String = when {
    already.isEmpty() -> final
    final.startsWith(already) -> final.substring(already.length)
    already.contains(final) -> ""
    else -> final
}
