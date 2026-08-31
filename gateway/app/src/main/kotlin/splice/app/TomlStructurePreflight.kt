// NEW: the pre-decode structural guards for splice.toml, extracted verbatim from TopologyLoader
// (concentration, 2026-08-31 — the DR-44 header guard tipped that file into band HIGH; the
// masker + guards are one concern with one call site, so they moved together). ktoml accepts
// several shapes the TOML spec forbids — a duplicated models key, a reopened table, a roster
// spelled as an array of strings — and each would otherwise merge or loop silently. These run
// on MASKED text (strings and comments blanked) so quoted content cannot fake or hide structure.
package splice.app

internal object TomlStructurePreflight {

    // ktoml loops instead of rejecting a head roster spelled as an array of strings. Mask quoted
    // text/comments first; '?' keeps a real quoted array element visible to this pre-decode guard.
    private val MODEL_ARRAY_ASSIGNMENT = Regex("(?<![A-Za-z0-9_-])models[ \\t]*=[ \\t]*\\[")

    // DR-44b twin: LINE-START assignments only — an inline-table `x = { models = [...] }` or a
    // dotted `provider.models` is a DIFFERENT key and must not count toward the duplicate scan.
    private val MODELS_LINE_ASSIGNMENT = Regex("(?m)^[ \\t]*models[ \\t]*=")
    private val TABLE_HEADER = Regex("(?m)^[ \\t]*\\[")

    fun check(text: String) {
        val structure = TomlStructureMasker(text).mask()
        validateInlineModelArrays(structure)
        rejectDuplicateModelKeys(structure)
    }

    /** DR-44b: TOML forbids a duplicated key, but ktoml accepts it silently (proven by the red
     *  arm: a second `models = [...]` line parsed with nothing thrown) — so a stale roster line
     *  kept retired models exposed in the picker with nothing red anywhere. Counted per table
     *  section over the masked text, so strings and comments cannot fake or hide a line.
     *
     *  DR-44 redo (codex bypass): the per-section count alone let a REOPENED table smuggle the
     *  same union — `[heads.claude-grok]` twice, one models line each, is one line per section
     *  while ktoml merges both bodies (the spec forbids redefining a table). So a repeated
     *  single-bracket header spelling is rejected too. Only the EXACT raw respelling needs this:
     *  quoted (`[heads."claude-grok"]`) and whitespace (`[ heads . claude-grok ]`) variants
     *  already fail loudly inside ktoml before any union (probed), and array-of-tables
     *  (`[[providers.xai.models]]`) legitimately repeats and stays out of the check. */
    private fun rejectDuplicateModelKeys(structure: String) {
        val bounds = TABLE_HEADER.findAll(structure).map { it.range.first }.toList() + structure.length
        val seenHeaders = HashSet<String>()
        var sectionStart = 0
        for (end in bounds) {
            // DR-96: a header at offset 0 makes the first bound a zero-width slice — skip it, or
            // the first table would be seen twice (once here, once as its own section).
            if (end == sectionStart) continue
            val section = structure.substring(sectionStart, end)
            val header = structure.substring(sectionStart).lineSequence().first().trim()
            // DR-96: gate on the SHAPE of the slice's first line, not on the offset. The old
            // `sectionStart > 0` preamble skip also skipped REGISTERING the first table of a
            // header-first file, so one exact reopen of it passed and ktoml merged both bodies —
            // the DR-44-redo scar, resurrected for the offset-0 table. A preamble slice can never
            // start with '[' (that line would itself be a TABLE_HEADER bound), so the shape test
            // is exactly the old preamble exclusion plus the missing first-table registration.
            if (header.startsWith("[") && !header.startsWith("[[")) {
                require(seenHeaders.add(header)) {
                    "table $header is defined twice — TOML forbids redefining a table and ktoml " +
                        "silently merges both bodies (a stale roster would ride the union); keep " +
                        "one section per table"
                }
            }
            require(MODELS_LINE_ASSIGNMENT.findAll(section).count() <= 1) {
                "duplicate models key in ${header.ifEmpty { "the preamble" }} — TOML forbids it " +
                    "and ktoml silently merges; keep exactly one models = [...] line per head"
            }
            sectionStart = end
        }
    }

    private fun validateInlineModelArrays(structure: String) {
        MODEL_ARRAY_ASSIGNMENT.findAll(structure).forEach { assignment ->
            val valueStart = assignment.range.last + 1
            val firstElement = structure.asSequence()
                .drop(valueStart)
                .firstOrNull { !it.isWhitespace() }
            require(firstElement == '{' || firstElement == ']') {
                "models must be an array of inline tables; write models = [{ id = \"...\" }]"
            }
        }
    }
}

private class TomlStructureMasker(private val text: String) {
    private val masked = StringBuilder(text)

    fun mask(): String {
        var index = 0
        while (index < text.length) {
            val keyLength = quotedModelsKeyLength(index)
            index = when {
                keyLength > 0 -> preserveModelsKey(index, keyLength)
                text[index] == '#' -> maskComment(index)
                text.startsWith("\"\"\"", index) -> maskQuoted(index, "\"\"\"", escapes = true)
                text.startsWith("'''", index) -> maskQuoted(index, "'''", escapes = false)
                text[index] == '"' -> maskQuoted(index, "\"", escapes = true)
                text[index] == '\'' -> maskQuoted(index, "'", escapes = false)
                else -> index + 1
            }
        }
        return masked.toString()
    }

    private fun quotedModelsKeyLength(start: Int): Int {
        val token = when {
            text.startsWith("\"models\"", start) -> "\"models\""
            text.startsWith("'models'", start) -> "'models'"
            else -> return 0
        }
        var cursor = start + token.length
        while (cursor < text.length && isHorizontalSpace(text[cursor])) cursor++
        return if (text.getOrNull(cursor) == '=') token.length else 0
    }

    private fun preserveModelsKey(start: Int, length: Int): Int {
        "models".padEnd(length).forEachIndexed { offset, char -> masked.setCharAt(start + offset, char) }
        return start + length
    }

    private fun maskComment(start: Int): Int {
        var cursor = start
        while (cursor < masked.length && !isLineBreak(masked[cursor])) {
            masked.setCharAt(cursor++, ' ')
        }
        return cursor
    }

    private fun maskQuoted(start: Int, delimiter: String, escapes: Boolean): Int {
        masked.setCharAt(start, '?')
        maskRange(start + 1, start + delimiter.length)
        var cursor = start + delimiter.length
        while (cursor < text.length) {
            if (text.startsWith(delimiter, cursor)) {
                val closingLength = closingDelimiterLength(cursor, delimiter)
                maskRange(cursor, cursor + closingLength)
                return cursor + closingLength
            }
            val escaped = escapes && text[cursor] == '\\'
            val escapePair = escaped && cursor + 1 < text.length
            val count = if (escapePair) 2 else 1
            maskRange(cursor, cursor + count)
            cursor += count
        }
        return cursor
    }

    private fun closingDelimiterLength(start: Int, delimiter: String): Int {
        if (delimiter.length == 1) return 1
        var length = delimiter.length
        val maxLength = delimiter.length + 2
        while (length < maxLength && text.getOrNull(start + length) == delimiter.first()) length++
        return length
    }

    private fun maskRange(start: Int, end: Int) {
        for (index in start until end) {
            if (!isLineBreak(masked[index])) masked.setCharAt(index, ' ')
        }
    }

    private fun isHorizontalSpace(char: Char): Boolean = char == ' ' || char == '\t'
    private fun isLineBreak(char: Char): Boolean = char == '\r' || char == '\n'
}
