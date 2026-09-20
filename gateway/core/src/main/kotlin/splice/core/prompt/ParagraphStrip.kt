// NEW: V4-170 (operator ask 2026-09-19) — the ONE place a `strip` layer's matching lives. A strip
// layer's text is not prompt text: it is a pattern list, one regex per non-empty, non-`#` line, and
// the layer DELETES every blank-line-separated paragraph of the client's own system text that any
// pattern matches. Everything else rides through byte-identically: unmatched paragraphs, the client's
// block order, its cache_control breakpoints — so the prompt cache still warms from turn two and the
// dynamic blocks a session needs (memory dir, environment, agent roster, MCP instructions) survive,
// which is exactly what `replace` cannot offer (HeadSystemPrompt.kt's SystemPromptMode KDoc).
//
// The three dialect seams (passthrough blocks, chat system-role messages, responses instructions)
// each know their wire's shape and hand this class the text; it knows paragraphs and patterns and
// nothing about JSON. A pattern that does not compile is a CONFIG ERROR AT LOAD (HeadSystemPrompt
// parses the list when it resolves the layer), never a layer that silently strips nothing.
package splice.core.prompt

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText

/** Paragraph delimiter: a blank line. Claude Code's operating text is paragraphs and `#` headings
 *  separated exactly so; a heading is its own paragraph and a pattern names it when it wants it. */
private const val PARAGRAPH_BREAK = "\n\n"
private const val COMMENT = "#"

/** [text] is a layer's pattern list; [source] names the layer in the load error. */
public class ParagraphStrip(text: String, source: String) {

    private val patterns: List<Regex> = parse(text, source)

    /** [text] with every paragraph any pattern matches (a find, not a full match) removed. A text no
     *  pattern touches is returned as the same instance, so a caller can tell "nothing to strip" from
     *  "stripped to nothing" by identity and leave the wire's bytes alone. */
    public fun strip(text: String): String {
        val paragraphs = text.split(PARAGRAPH_BREAK)
        val kept = paragraphs.filterNot { paragraph -> patterns.any { it.containsMatchIn(paragraph) } }
        return if (kept.size == paragraphs.size) text else kept.joinToString(PARAGRAPH_BREAK)
    }

    private fun parse(text: String, source: String): List<Regex> {
        val lines = text.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith(COMMENT) }
        require(lines.isNotEmpty()) { "$source strip layer names no pattern: every line is blank or a # comment" }
        return lines.map { line ->
            Cancellables.runCatchingCancellable { Regex(line) }.getOrElse { failure ->
                throw IllegalArgumentException(
                    "$source strip pattern is not a regex: `$line` (${SafeFailureText.render(failure)})",
                    failure,
                )
            }
        }
    }
}
