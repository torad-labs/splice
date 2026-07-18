// PORT-OF: server/src/reasoning/mirror.mjs @ 4ca99f7 — invariants (L2): ONE mirrorInto serves
// stream and non-stream paths (v29 had two drifting copies; an ast-grep wall pins the
// definition to THIS file); wire contract v25-v29: "\n[reasoning summary]\n<trimmed>\n" —
// transcript tooling keys on this shape; gates: never on compact, only showReasoning=='text',
// only when the trimmed summary >= MIRROR_MIN_CHARS. Thresholds live in :core (shared with
// the dialect's promote step).
package splice.gateway.reasoning

import splice.core.turn.MIRROR_MIN_CHARS
import splice.core.turn.ReasoningDisplay
import splice.core.wire.ContentBlock
import splice.core.wire.ThinkingBlock
import splice.spi.WireSink

/** WIRE CONTRACT (external): the mirror block format transcript tooling keys on. */
public fun mirrorWireText(thinking: String): String = "\n[reasoning summary]\n${thinking.trim()}\n"

/**
 * Mirror the turn's thinking into the transcript as a visible text block.
 * Returns true when the mirror was emitted.
 */
public suspend fun mirrorInto(
    sink: WireSink,
    thinkingText: String?,
    showReasoning: ReasoningDisplay,
    compact: Boolean,
): Boolean {
    if (compact) return false // compact is a text-only summarizer turn
    // Gate cascade (ported contract): only when reasoning is shown as text AND the trimmed
    // summary clears the wire threshold; order preserved (showReasoning gate before length gate).
    val t = thinkingText.orEmpty().trim()
    if (showReasoning != ReasoningDisplay.TEXT || t.length < MIRROR_MIN_CHARS) return false
    sink.addTextBlock(mirrorWireText(t))
    return true
}

/** Join the thinking blocks of an Anthropic content list (non-stream path). */
public fun extractThinking(content: List<ContentBlock>): String =
    content.filterIsInstance<ThinkingBlock>()
        .map { it.thinking.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
