// NEW: the pre-decode structural guards for splice.toml, extracted verbatim from TopologyLoader
// (concentration, 2026-08-31 — the DR-44 header guard tipped that file into band HIGH; the
// masker + guards are one concern with one call site, so they moved together). ktoml accepts
// several shapes the TOML spec forbids — a duplicated models key, a reopened table, a roster
// spelled as an array of strings — and each would otherwise merge or loop silently. These run
// on MASKED text (strings and comments blanked) so quoted content cannot fake or hide structure.
package splice.topology

import splice.core.util.TopologyStructureFailure

internal object TomlStructurePreflight {

    // ktoml loops instead of rejecting a head roster spelled as an array of strings. Mask quoted
    // text/comments first; '?' keeps a real quoted array element visible to this pre-decode guard.
    private val MODEL_ARRAY_ASSIGNMENT = Regex("(?<![A-Za-z0-9_-])models[ \\t]*=[ \\t]*\\[")

    // LINE-START assignments only, read on the masked text: a key of bare characters or a quoted key (which the
    // masker leaves as '?' and blanks, so an '=' inside it never ends the key), dotted parts allowed. An inline-table
    // `x = { models = [...] }` is a key of its own table and never counts toward the duplicate scan.
    private val KEY_ASSIGNMENT =
        Regex("(?m)^[ \\t]*((?:[A-Za-z0-9_-]+|\\?[ ]*)(?:[ \\t]*\\.[ \\t]*(?:[A-Za-z0-9_-]+|\\?[ ]*))*)[ \\t]*=")
    private val TABLE_HEADER = Regex("(?m)^[ \\t]*\\[")

    fun check(text: String) {
        failures(text).firstOrNull()?.let { throw it }
    }

    /** EVERY structural problem in [text], in the order [check] reports the first of them. Each carries the
     *  table and line it concerns and never a value, so a refusal can list them all at once. */
    fun failures(text: String): List<IllegalArgumentException> {
        val structure = TomlStructureMasker(text).mask()
        return inlineModelArrayFailures(structure) + duplicateKeyFailures(text, structure) +
            inlineTableFailures(text, structure)
    }

    /** DR-44b: TOML forbids a duplicated key, but ktoml accepts it silently (proven by the red
     *  arm: a second `models = [...]` line parsed with nothing thrown) — so a stale roster line
     *  kept retired models exposed in the picker with nothing red anywhere, and a second
     *  `"/v1" = ...` under `[server.aliases]` replaced the first. Every key is counted per table
     *  section: its place is found on the masked text, so strings and comments cannot fake or hide
     *  a line, and its spelling is read from [text] at the same offsets, one layer of quotes off.
     *
     *  DR-44 redo (codex bypass): the per-section count alone let a REOPENED table smuggle the
     *  same union — `[heads.claude-grok]` twice, one models line each, is one line per section
     *  while ktoml merges both bodies (the spec forbids redefining a table). So a repeated
     *  single-bracket header spelling is rejected too. Only the EXACT raw respelling needs this:
     *  quoted (`[heads."claude-grok"]`) and whitespace (`[ heads . claude-grok ]`) variants
     *  already fail loudly inside ktoml before any union (probed), and array-of-tables
     *  (`[[providers.xai.models]]`) legitimately repeats and stays out of the check. */
    private fun duplicateKeyFailures(text: String, structure: String): List<IllegalArgumentException> {
        val found = mutableListOf<IllegalArgumentException>()
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
                if (!seenHeaders.add(header)) {
                    found += TopologyStructureFailure(
                        "table ${tableName(header)} (line ${lineOf(structure, sectionStart)}) " +
                            "is defined twice: TOML forbids redefining a table and ktoml silently merges both " +
                            "bodies (a stale roster would ride the union); keep one section per table",
                    )
                }
            }
            val written = KEY_ASSIGNMENT.findAll(section).map { assignment ->
                val key = requireNotNull(assignment.groups[1]).range
                unquoted(text.substring(sectionStart + key.first, sectionStart + assignment.range.last).trim())
            }
            for (key in written.groupingBy { it }.eachCount().filterValues { it > 1 }.keys) {
                found += TopologyStructureFailure(
                    "duplicate key $key in ${tableName(header)} (line ${lineOf(structure, sectionStart)}): " +
                        "TOML forbids writing a key twice in one table and ktoml silently keeps or merges them; " +
                        "keep exactly one $key line",
                )
            }
            sectionStart = end
        }
        return found
    }

    /** The table a finding names: the bracketed header and nothing after it. A section that opens with a bare
     *  key (the preamble) is named as the preamble, because its first line is a key and a value the operator
     *  wrote, and a finding goes to the refusal and the daemon log. */
    private fun tableName(header: String): String {
        if (!header.startsWith("[")) return "the preamble"
        val close = header.indexOf(']')
        val brackets = header.takeWhile { it == '[' }.length
        return if (close < 0) "the preamble" else header.substring(0, minOf(header.length, close + brackets))
    }

    /** [key] with one layer of TOML quotes off, so `"/v1"` and `'/v1'` count as the one key they are. */
    private fun unquoted(key: String): String {
        val quoted = key.length > 1 && key.first() in "\"'" && key.last() == key.first()
        return if (quoted) key.substring(1, key.length - 1) else key
    }

    /** The 1-based line of [offset] in the masked text, which keeps every newline of the file. */
    private fun lineOf(structure: String, offset: Int): Int = structure.take(offset).count { it == '\n' } + 1

    /** ktoml keeps the last of a key written twice inside one inline table (`aliases = { "/v1" = "a", "/v1" = "b" }`,
     *  a `model_slots` map) as it does in a table section. Each `{ ... }` is walked on the masked text, so a brace,
     *  comma or '=' inside a string or comment is none, and its own keys, one per comma-separated pair with nested
     *  tables and arrays apart, are read from [text] at the same offsets, one layer of quotes off. */
    private fun inlineTableFailures(text: String, structure: String): List<IllegalArgumentException> {
        val found = mutableListOf<IllegalArgumentException>()
        val open = ArrayDeque<InlineLevel>()
        structure.forEachIndexed { at, char ->
            val table = open.lastOrNull()?.takeIf { it.table }
            when (char) {
                '{', '[' -> open.addLast(InlineLevel(at, table = char == '{'))
                '}', ']' -> open.removeLastOrNull()?.let { closed -> found += repeatedIn(closed, structure) }
                ',' -> table?.pairStart = at + 1
                '=' -> table?.takeIf { it.pairStart >= 0 }?.let { level ->
                    level.keys += unquoted(text.substring(level.pairStart, at).trim())
                    level.pairStart = -1
                }
            }
        }
        return found
    }

    /** A finding for each key the inline table [closed] names more than once. */
    private fun repeatedIn(closed: InlineLevel, structure: String): List<IllegalArgumentException> =
        closed.repeated().map { key ->
            TopologyStructureFailure(
                "duplicate key $key in an inline table (line ${lineOf(structure, closed.open)}): TOML forbids " +
                    "writing a key twice in one table and ktoml silently keeps the last; keep exactly one $key",
            )
        }

    /** One open `{` (a [table]) or `[` at offset [open]: the keys a table has named so far, and where its pair being
     *  read began, or -1 once that pair's '=' is past. */
    private class InlineLevel(val open: Int, val table: Boolean) {
        val keys = mutableListOf<String>()
        var pairStart = open + 1

        /** Every key this table named more than once. */
        fun repeated(): Set<String> = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    private fun inlineModelArrayFailures(structure: String): List<IllegalArgumentException> =
        MODEL_ARRAY_ASSIGNMENT.findAll(structure).mapNotNull { assignment ->
            val valueStart = assignment.range.last + 1
            val firstElement = structure.asSequence()
                .drop(valueStart)
                .firstOrNull { !it.isWhitespace() }
            if (firstElement == '{' || firstElement == ']') {
                null
            } else {
                TopologyStructureFailure(
                    "models (line ${lineOf(structure, assignment.range.first)}) must be an array of inline tables: " +
                        "write models = [{ id = \"...\" }]",
                )
            }
        }.toList()
}

/** V4-83: `public`, not `private` — `splice add-model` (in :app) edits the head roster by offset and must
 *  find its structure on the same mask this preflight uses (a comment or a string must not be able
 *  to fake or hide an array bracket in EITHER reader). Offsets are preserved byte-for-byte: every
 *  masked character is replaced in place, never removed. */
public class TomlStructureMasker(private val text: String) {
    private val masked = StringBuilder(text)

    public fun mask(): String {
        var index = 0
        while (index < text.length) {
            val key = quotedAssignmentKey(index)
            index = when {
                key != null -> preserveKey(index, key)
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

    private fun quotedAssignmentKey(start: Int): String? {
        val quote = text[start]
        if (quote != '"' && quote != '\'') return null
        val end = text.indexOf(quote, start + 1)
        if (end < 0) return null
        val key = text.substring(start + 1, end)
        var cursor = end + 1
        while (cursor < text.length && isHorizontalSpace(text[cursor])) cursor++
        return key.takeIf {
            it.lowercase() in setOf("models", "model_slots", "opus", "sonnet", "haiku", "fable") &&
                text.getOrNull(cursor) == '='
        }
    }

    private fun preserveKey(start: Int, key: String): Int {
        val length = key.length + 2
        key.padEnd(length).forEachIndexed { offset, char -> masked.setCharAt(start + offset, char) }
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
