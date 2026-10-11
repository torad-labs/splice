// NEW: V4-170 (operator ask 2026-09-19), repaired by V4-172 after two adversarial reviews — the ONE
// place a `strip` layer's matching lives. A strip layer's text is not prompt text: it is a pattern
// list, one regex per non-empty, non-`#` line, and the layer DELETES every paragraph of the client's
// own system text that any pattern matches. Everything else rides through byte-identically:
// unmatched paragraphs, THEIR ORIGINAL SEPARATORS, the client's block order and its cache_control
// breakpoints — so the prompt cache still warms from turn two, which `replace` cannot offer.
//
// A PARAGRAPH IS SEPARATED BY A BLANK LINE, IN WHATEVER LINE ENDING THE TEXT USES (V4-172). The
// first cut of this class split on the literal two-character "\n\n", and `\r\n\r\n` contains no such
// adjacency: CRLF text was ONE paragraph, so a single matching pattern deleted the ENTIRE system
// field. The same literal made a gap of two blank lines leave the next paragraph starting with a
// newline, which silently unhooks every `^`-anchored pattern. Both are why the separators are found
// with a regex and re-emitted VERBATIM rather than normalized: a kept paragraph and the whitespace
// around it reach the wire exactly as the client wrote them.
//
// THE COUNT IS THE SIGNAL, NOT A BOOLEAN (V4-172). "Nothing matched", "some patterns matched" and
// "every paragraph matched" are three different operational states, and a strip layer is configured
// once and then trusted for every turn afterwards — a pattern that goes stale on a Claude Code
// upgrade must not be indistinguishable from one that works. [strip] returns how many paragraphs it
// removed; TurnPrompts stamps it on the turn's perf row.
package splice.core.prompt

import splice.core.util.Cancellables
import splice.core.util.SafeFailureText
import java.util.concurrent.ConcurrentHashMap

/** A blank line in any line ending — CR, LF or CRLF — and the WHOLE run of them: a gap of two blank
 *  lines is one separator, so the paragraph after it does not begin with a stray newline that would
 *  unhook every `^`-anchored pattern (V4-172). */
private val PARAGRAPH_BREAK = Regex("\\R(?:[ \\t]*\\R)+")
private const val COMMENT = "#"

/** Compiled pattern lists, keyed by the layer text that declared them. A layer is configured once and
 *  read on every turn at every seam, so without this each turn recompiled the whole list on the
 *  request path (V4-172). Bounded by the number of distinct strip layers in the topology. */
private val COMPILED = ConcurrentHashMap<String, List<Regex>>()

/** The result of one strip: [text] with [removed] paragraphs gone. When [removed] is 0, [text] is the
 *  SAME INSTANCE that went in, so a seam can leave the wire's bytes untouched by identity. */
public data class Stripped(val text: String, val removed: Int)

/** [text] is a layer's pattern list; [source] names the layer in the load error. */
public class ParagraphStrip(text: String, source: String) {

    private val patterns: List<Regex> = COMPILED.computeIfAbsent(text) { parse(it, source) }

    /** [text] with every paragraph any pattern matches (a find, not a full match) removed, and the
     *  count of those paragraphs. Kept paragraphs and the separators between them are re-emitted
     *  verbatim. */
    public fun strip(text: String): Stripped {
        val breaks = PARAGRAPH_BREAK.findAll(text).toList()
        if (breaks.isEmpty()) return oneParagraph(text)
        val kept = StringBuilder()
        var removed = 0
        var pending: String? = null
        var start = 0
        for (edge in breaks) {
            val next = keep(kept, text.substring(start, edge.range.first), pending, edge.value)
            if (next == null) removed += 1
            pending = next ?: pending ?: edge.value
            start = edge.range.last + 1
        }
        if (keep(kept, text.substring(start), pending, "") == null) removed += 1
        return if (removed == 0) Stripped(text, 0) else Stripped(kept.toString(), removed)
    }

    /** Appends [paragraph] to [kept] behind [pending] — the separator that followed the previous kept
     *  paragraph, so a removed paragraph takes exactly one separator with it and never leaves a
     *  double gap — and returns [next], the separator to hold for the paragraph after this one. Null
     *  when a pattern matched and the paragraph is dropped instead. */
    private fun keep(kept: StringBuilder, paragraph: String, pending: String?, next: String): String? {
        if (patterns.any { it.containsMatchIn(paragraph) }) return null
        if (kept.isNotEmpty()) kept.append(pending.orEmpty())
        kept.append(paragraph)
        return next
    }

    /** A text with no blank line is one paragraph: all of it, or none of it. */
    private fun oneParagraph(text: String): Stripped =
        if (patterns.any { it.containsMatchIn(text) }) Stripped("", 1) else Stripped(text, 0)

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
