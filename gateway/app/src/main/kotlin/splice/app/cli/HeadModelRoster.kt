// NEW: V4-83 split (concentration ratchet, 2026-09-17) — the roster TEXT editor is its own concern —
// finding structure on the TOML mask and splicing ids into `[heads.KEY].models` byte-preservingly —
// separate from the add-model verb that prompts, plans and writes. AddModelVerb consumes it only
// through the RosterEditor seam.
package splice.app.cli

import splice.app.TomlStructureMasker

/** The roster edit as a role: given the config text, the head key and the ids to add, return the
 *  edited text. The verb's fail-closed re-parse sits behind this seam so a test can hand it an
 *  editor that produces unparseable TOML and prove nothing is written (wall kt-no-lambda-seam). */
internal fun interface RosterEditor {
    operator fun invoke(text: String, headKey: String, ids: List<String>): String
}

internal class HeadModelArray {

    fun withAdded(text: String, headKey: String, ids: List<String>): String {
        val mask = TomlStructureMasker(text).mask()
        val open = arrayStart(text, mask, headKey)
            ?: throw AddRefused(
                "head '$headKey' declares a model roster splice cannot edit: expected a " +
                    "`models = [` line under [heads.$headKey]. Add ${ids.joinToString(", ")} by hand.",
            )
        val close = matchingBracket(mask, open)
            ?: throw AddRefused("head '$headKey' has an unterminated models = [ array")
        val missing = ids.filter { it !in rostered(text, mask, open + 1, close) }
        if (missing.isEmpty()) return text
        // The insertion point is the end of the array's last STRUCTURAL byte, so a comma lands
        // before a trailing comment rather than inside it, and the comment survives untouched.
        val end = lastStructure(mask, open + 1, close)
        val kept = text.substring(open + 1, end)
        val rest = text.substring(end, close)
        val closeIndent = if ('\n' in rest) rest.substringAfterLast('\n') else ""
        val added = missing.joinToString("") { "  { id = \"$it\" },\n" }
        return text.substring(0, open + 1) + kept + (if (kept.endsWith(",") || kept.isEmpty()) "" else ",") +
            rest.dropLast(closeIndent.length).ifEmpty { "\n" } + added + closeIndent + text.substring(close)
    }

    /** Index of the `[` that opens `models = [` inside the `[heads.KEY]` table, or null. Review
     *  2026-09-17 (5): the header spelling is matched on the whole LINE, so a trailing comment
     *  (`[heads.openrouter]  # primary head`) and a quoted key (`[heads."openrouter"]`) are
     *  tolerated instead of throwing a refusal at an operator who wrote legal TOML. */
    private fun arrayStart(text: String, mask: String, headKey: String): Int? {
        val header = Regex("^[ \\t]*\\[heads\\.[\"']?\\Q$headKey\\E[\"']?][ \\t]*(?:#.*)?$")
        val line = TABLE_HEADER.findAll(mask).map { it.range.first }
            .firstOrNull { header.matches(lineAt(text, it)) } ?: return null
        val bodyStart = line + lineAt(text, line).length
        val bodyEnd = TABLE_HEADER.find(mask, bodyStart)?.range?.first ?: text.length
        val array = MODELS_ARRAY.find(mask.substring(bodyStart, bodyEnd)) ?: return null
        return bodyStart + array.range.last
    }

    /** Bracket depth on the MASK, where a `[` or `]` inside a comment or a string is already blank. */
    private fun matchingBracket(mask: String, open: Int): Int? {
        var depth = 0
        for (index in open until mask.length) {
            when (mask[index]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return index
            }
        }
        return null
    }

    /** The ids the array already names: each `id =` the MASK still shows is structure, and its value
     *  is read from the original text at that offset (the mask blanks string bodies). */
    private fun rostered(text: String, mask: String, from: Int, to: Int): Set<String> =
        ID_ASSIGNMENT.findAll(mask.substring(from, to))
            .mapNotNull { quotedValue(text, from + it.range.last) }
            .toSet()

    private fun quotedValue(text: String, quote: Int): String? {
        val end = text.indexOf(text[quote], quote + 1)
        return if (end < 0) null else text.substring(quote + 1, end)
    }

    /** End (exclusive) of the last non-whitespace byte of the mask in [from, to) — comments are
     *  whitespace there, so this is the last byte of real array content. */
    private fun lastStructure(mask: String, from: Int, to: Int): Int {
        var index = to
        while (index > from && mask[index - 1].isWhitespace()) index--
        return index
    }

    private fun lineAt(text: String, start: Int): String =
        text.substring(start, text.indexOf('\n', start).takeIf { it >= 0 } ?: text.length)
}

private val ID_ASSIGNMENT = Regex("(?<![A-Za-z0-9_-])id[ \\t]*=[ \\t]*\\?")
private val TABLE_HEADER = Regex("(?m)^[ \\t]*\\[")
private val MODELS_ARRAY = Regex("(?m)^[ \\t]*models[ \\t]*=[ \\t]*\\[")
