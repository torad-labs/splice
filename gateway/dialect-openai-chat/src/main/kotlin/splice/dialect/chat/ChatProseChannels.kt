// PORT-OF: ChatStreamTranslator.kt @ e2e0d0f — invariants unchanged: the buffers now live with the
// code that writes them instead of on the god class (state moved WITH the behaviour that owns it).
package splice.dialect.chat

import kotlinx.serialization.json.JsonObject
import splice.core.index.WireBlockIndex
import splice.core.util.JsonScalars
import splice.spi.WireSink

/** The chat dialect's prose channels: text and thinking blocks, their buffers, and the fold rules
 *  that decide what a delta or a final message still needs to emit. */
internal class ChatProseChannels {

    internal var textBlock: WireBlockIndex? = null
    internal var thinkingBlock: WireBlockIndex? = null
    internal val textBuf = StringBuilder()
    internal val thinkingBuf = StringBuilder()
    internal var emittedText = false

    // CX-09: a thinking block reaching the sink is content the client got; the empty-turn
    // honesty gate reads this so it never grades a thinking-only turn as empty.
    internal var emittedThinking = false

    private val fold = ChatProseFold()

    /** DR-143: opening one prose block CLOSES the other, because Anthropic's grammar is one block
     *  at a time — start, deltas, stop, then the next. The thinking block used to be opened lazily
     *  and never closed until closeAll, so a reasoning-then-answer turn put start(0,thinking),
     *  start(1,text) and both stops at the very end on the wire, with block 0 still open across all
     *  of block 1's content. Both sibling dialects respect the grammar — passthrough forwards the
     *  upstream's own stop per block, Responses closes reasoning at item-done — and chat was the
     *  outlier; grok rides this dialect precisely because it streams readable CoT, so overlapping
     *  blocks were the normal shape of every grok turn that reasons before answering. Reusing an
     *  already-open block of the same kind stays a no-op, so repeated same-kind deltas do not churn
     *  blocks. */
    private suspend fun ensureThinking(sink: WireSink): WireBlockIndex {
        textBlock?.let {
            sink.closeBlock(it)
            textBlock = null
        }
        return thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
    }

    /** The [ensureThinking] twin: a text block closes any open thinking block first. */
    private suspend fun ensureText(sink: WireSink): WireBlockIndex {
        thinkingBlock?.let {
            sink.closeBlock(it)
            thinkingBlock = null
        }
        return textBlock ?: sink.openText().also { textBlock = it }
    }

    internal suspend fun applyDeltaProse(delta: JsonObject, sink: WireSink) {
        // Vendors disagree on the cleartext CoT field name:
        // DeepSeek/xAI chat → reasoning_content; some OpenRouter/vLLM → reasoning; a few → thinking.
        fold.reasoningDeltaText(delta)?.let { r ->
            val idx = ensureThinking(sink)
            emittedThinking = true // CX-09: a thinking block is on the wire from here on
            thinkingBuf.append(r)
            sink.thinkingDelta(idx, r)
        }
        JsonScalars.strOrEmpty(delta["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val idx = ensureText(sink)
            emittedText = true
            textBuf.append(c)
            sink.textDelta(idx, c)
        }
    }

    /** The reasoning + text halves of the final-message fold, both prefix-aware via
     *  [ChatProseFold.unseenSuffix]. */
    internal suspend fun foldFinalProse(msg: JsonObject, sink: WireSink) {
        fold.reasoningDeltaText(msg)?.let { r ->
            val toEmit = fold.unseenSuffix(thinkingBuf.toString(), r)
            if (toEmit.isNotEmpty()) {
                val idx = ensureThinking(sink)
                emittedThinking = true // CX-09: a thinking block is on the wire from here on
                thinkingBuf.append(toEmit)
                sink.thinkingDelta(idx, toEmit)
            }
        }
        JsonScalars.strOrEmpty(msg["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val toEmit = fold.unseenSuffix(textBuf.toString(), c)
            if (toEmit.isNotEmpty()) {
                val idx = ensureText(sink)
                emittedText = true
                textBuf.append(toEmit)
                sink.textDelta(idx, toEmit)
            }
        }
    }
}
