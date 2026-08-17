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

    internal suspend fun applyDeltaProse(delta: JsonObject, sink: WireSink) {
        // Vendors disagree on the cleartext CoT field name:
        // DeepSeek/xAI chat → reasoning_content; some OpenRouter/vLLM → reasoning; a few → thinking.
        fold.reasoningDeltaText(delta)?.let { r ->
            val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
            emittedThinking = true // CX-09: a thinking block is on the wire from here on
            thinkingBuf.append(r)
            sink.thinkingDelta(idx, r)
        }
        JsonScalars.strOrEmpty(delta["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val idx = textBlock ?: sink.openText().also { textBlock = it }
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
                val idx = thinkingBlock ?: sink.openThinking().also { thinkingBlock = it }
                emittedThinking = true // CX-09: a thinking block is on the wire from here on
                thinkingBuf.append(toEmit)
                sink.thinkingDelta(idx, toEmit)
            }
        }
        JsonScalars.strOrEmpty(msg["content"]).takeIf { it.isNotEmpty() }?.let { c ->
            val toEmit = fold.unseenSuffix(textBuf.toString(), c)
            if (toEmit.isNotEmpty()) {
                val idx = textBlock ?: sink.openText().also { textBlock = it }
                emittedText = true
                textBuf.append(toEmit)
                sink.textDelta(idx, toEmit)
            }
        }
    }
}
