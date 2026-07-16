// PORT-OF: pickModelText/isWeakSummaryText from server/src/codex/translate-response.mjs
// @ 4ca99f7 — invariants (L4): promote only ever promotes MODEL content; "no model text
// returned" is weak. Provider-neutral (used by the gateway pipeline's promote-to-text), so it
// lives in :core, not the dialect.
package splice.core.turn

public data class PickedText(val text: String, val source: String)

private val weakRe = Regex("no model text returned", RegexOption.IGNORE_CASE)

public fun isWeakSummaryText(text: String?): Boolean {
    val t = text.orEmpty().trim()
    return t.isEmpty() || weakRe.containsMatchIn(t)
}

/** Prefer real model text, then reasoning summary promoted to text (model content only, L4). */
public fun pickModelText(thinkingBuf: String, textBuf: String): PickedText {
    val text = textBuf.trim()
    val thinking = thinkingBuf.trim()
    return when {
        text.isNotEmpty() && !isWeakSummaryText(text) -> PickedText(text, "model_text")
        thinking.length >= PROMOTE_MIN_CHARS -> PickedText(thinking, "model_thinking")
        text.isNotEmpty() -> PickedText(text, "model_text_weak")
        else -> PickedText("", "empty")
    }
}
