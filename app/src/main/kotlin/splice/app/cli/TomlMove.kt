// NEW: gateway-no-heads. The text edit behind the fix session's move: a value taken off its line in one table and put as
// `key = value` in another. The place is the one the file already uses: the table's own header, else the inline table
// (or the dotted keys) its parent assigns it as, else a new table at the end of the file. A table the file defines one
// way is never defined a second, which TOML refuses, and a line this cannot place with certainty is left untouched:
// the answer is null, and nothing is written. Unrelated fields and every other byte of the file stay as they were.
// Every line is read as part of its statement (TomlStatements): a value can run over several lines (a multi-line string,
// `"""` or `'''`, or an array), and a line inside one is text, never a table header or an assignment, however it reads:
// a `[providers.demo]` in a prompt's example is not that table.
package splice.app.cli

// why: a multi-line string may end in up to two quotes of its own right before its closing delimiter.
private const val TRAILING_QUOTES = 2

internal class TomlMove(private val lines: List<String>) {
    /** How the file defines a table whose parent it assigns it in: as an inline table, as dotted keys, or as something
     *  else (a scalar, which nothing can be added to). */
    private enum class Kind { INLINE, DOTTED, OTHER }

    /** The assignment at [index] of the table, the end of its section, and the name as the file spells it. */
    private data class Definition(val index: Int, val end: Int, val kind: Kind, val name: String)

    /** One `key = value` to put in the table, the value as the file wrote it, over every line it runs to. */
    data class Entry(val key: String, val said: TomlStatements.Written) {
        /** Whether it can sit inside an inline table: on one line, with no comment it would lose there. */
        val inlinable: Boolean get() = '\n' !in said.value && said.tail.isEmpty()

        fun line(prefix: String = ""): String = "$prefix$key = ${said.value}${said.tail}"
    }

    private val statements = TomlStatements(lines)

    /** The value the assignment beginning on [line] (1-based) gives, when it begins one. */
    fun valueAt(line: Int?): TomlStatements.Written? = line?.let { statements.value(it - 1) }

    /** The line (1-based) that assigns the bare [key] in the same section as [line], the table header above it down to
     *  the next, or null when that section assigns it on no line of its own. */
    fun lineOf(key: String, line: Int): Int? {
        val at = line - 1
        if (at !in lines.indices) return null
        val start = (at downTo 0).firstOrNull(statements::header) ?: -1
        val end = (at + 1 until lines.size).firstOrNull(statements::header) ?: lines.size
        return (start + 1 until end).firstOrNull { index -> names(statements, index) == listOf(key) }?.plus(1)
    }

    /** [lines] with each of [entries] in [table] (a dotted path as TOML spells it), in order, and the statements that
     *  begin on the line numbers [removed] (1-based) taken out whole; null when the file defines [table] in a way this
     *  cannot add to with certainty. */
    fun of(table: String, entries: List<Entry>, removed: Set<Int>): List<String>? {
        val kept = TomlStatements(lines.filterIndexed { index, _ -> statements.begins(index) + 1 !in removed })
        val path = TomlLine(table).dotted()
        val header = headerAt(kept, path)
        val moved = if (header >= 0) {
            kept.lines.take(header + 1) + entries.map { it.line() } + kept.lines.drop(header + 1)
        } else {
            placed(kept, path, table, entries)
        }
        return moved?.flatMap { it.split("\n") }
    }

    /** [entries] where the file defines the table [path] other than by its own header. */
    private fun placed(from: TomlStatements, path: List<String>, table: String, entries: List<Entry>): List<String>? {
        val found = definitionOf(from, path)
            ?: return from.lines + listOf("", "[$table]") + entries.map { it.line() }
        return when (found.kind) {
            Kind.INLINE -> entries.takeIf { all -> all.all(Entry::inlinable) }
                ?.let { inlined(from, found.index, it.joinToString(", ") { entry -> entry.line() }) }
            Kind.DOTTED -> dotted(from, found, path.last(), entries)
            Kind.OTHER -> null
        }
    }

    /** The line of the header `[path]`, however it is spelled (spacing, quoting), or -1. */
    private fun headerAt(from: TomlStatements, path: List<String>): Int = from.lines.indices.firstOrNull { index ->
        val text = from.lines[index].trim()
        val close = TomlLine(text).indexOutsideQuotes(']')
        from.header(index) && !text.startsWith("[[") && close > 0 && TomlLine(text.substring(1, close)).dotted() == path
    } ?: -1

    /** How the parent's section assigns the table [path] names, or null when it does not (the table is then new). */
    private fun definitionOf(from: TomlStatements, path: List<String>): Definition? {
        val parent = path.dropLast(1)
        val start = if (parent.isEmpty()) -1 else headerAt(from, parent)
        if (parent.isNotEmpty() && start < 0) return null
        val end = (start + 1 until from.lines.size).firstOrNull(from::header) ?: from.lines.size
        return (start + 1 until end).firstNotNullOfOrNull { index -> assignment(from, index, end, path.last()) }
    }

    /** The statement at [index] as a definition of [leaf], when it assigns that name (alone, or as the head of a dotted
     *  key). */
    private fun assignment(from: TomlStatements, index: Int, end: Int, leaf: String): Definition? {
        val equals = from.equalsAt(index)
        if (equals < 0) return null
        val line = from.lines[index]
        val key = line.substring(0, equals)
        val names = TomlLine(key).dotted()
        val inline = line.substring(equals + 1).trimStart().startsWith("{")
        return when {
            names.first() != leaf -> null
            names.size > 1 -> Definition(index, end, Kind.DOTTED, key.trim().substringBefore('.').trim())
            inline -> Definition(index, end, Kind.INLINE, key.trim())
            else -> Definition(index, end, Kind.OTHER, key.trim())
        }
    }

    /** [entry] added inside the inline table the line at [index] assigns, with its other fields and comment kept. An
     *  inline table written over several lines is not one this edits. */
    private fun inlined(from: TomlStatements, index: Int, entry: String): List<String>? {
        if (from.end(index) != index) return null
        val line = from.lines[index]
        val scan = TomlLine(line)
        val open = scan.indexOutsideQuotes('{', scan.indexOutsideQuotes('='))
        val close = if (open < 0) -1 else scan.closingBrace(open)
        if (close < 0) return null
        val fields = line.substring(open + 1, close).trim().trimEnd(',').trim()
        val joined = if (fields.isEmpty()) entry else "$fields, $entry"
        val edited = line.substring(0, open + 1) + " $joined " + line.substring(close)
        return from.lines.take(index) + edited + from.lines.drop(index + 1)
    }

    /** `name.<entry>` for each of [entries], added after the last statement of the section that spells the table as
     *  dotted keys. */
    private fun dotted(from: TomlStatements, table: Definition, leaf: String, entries: List<Entry>): List<String> {
        val last = from.end((table.index until table.end).last { index -> names(from, index).firstOrNull() == leaf })
        return from.lines.take(last + 1) + entries.map { it.line("${table.name}.") } + from.lines.drop(last + 1)
    }

    /** The names the key of the assignment beginning at [index] spells, or none when it begins no assignment. */
    private fun names(from: TomlStatements, index: Int): List<String> {
        val equals = from.equalsAt(index)
        return if (equals > 0) TomlLine(from.lines[index].substring(0, equals)).dotted() else emptyList()
    }
}

/** Which lines of splice.toml begin a statement, and where each one ends: every line the move takes for a header or a
 *  key is asked here first. */
internal class TomlStatements(val lines: List<String>) {
    /** A value as the file writes it, over every line it runs to, and what follows it on its last line ([tail]: the
     *  space and comment after it, or nothing). */
    data class Written(val value: String, val tail: String)

    /** For each line, the line its statement begins on. */
    private val first = IntArray(lines.size)

    /** For each line, the last line of its statement. */
    private val last = IntArray(lines.size)

    /** For each statement's first line, where its value stops on its last line: its trailing comment, or the end. */
    private val stops = IntArray(lines.size)

    init {
        var index = 0
        while (index < lines.size) {
            val end = endOf(index)
            for (row in index..end.row) {
                first[row] = index
                last[row] = end.row
            }
            stops[index] = end.column
            index = end.row + 1
        }
    }

    /** Whether the line at [index] (0-based) begins a statement, a blank line or a comment, rather than continuing a
     *  value from a line above. */
    fun opens(index: Int): Boolean = index in lines.indices && first[index] == index

    /** Whether the line at [index] is a table header (`[a.b]` or `[[a.b]]`). */
    fun header(index: Int): Boolean = opens(index) && lines[index].trimStart().startsWith("[")

    /** The `=` of the assignment the line at [index] begins, or -1 when it begins none. */
    fun equalsAt(index: Int): Int = if (opens(index)) equalsOf(index) else -1

    /** The line the statement holding the line at [index] begins on. */
    fun begins(index: Int): Int = first[index]

    /** The last line of the statement that begins at [index]. */
    fun end(index: Int): Int = last[index]

    /** The value the assignment beginning at [index] gives, or null when it begins none or gives nothing. */
    fun value(index: Int): Written? {
        val equals = equalsAt(index)
        if (equals < 0) return null
        val end = last[index]
        val said = lines.subList(index, end + 1).joinToString("\n")
            .substring(0, lines.subList(index, end).sumOf { it.length + 1 } + stops[index])
            .substring(equals + 1)
        val value = said.trim()
        val tail = said.substring(said.trimEnd().length) + lines[end].substring(stops[index])
        return Written(value, tail.takeIf(String::isNotBlank).orEmpty()).takeIf { value.isNotEmpty() }
    }

    private fun equalsOf(index: Int): Int {
        val text = lines[index].trimStart()
        return if (text.startsWith("#") || text.startsWith("[")) -1 else TomlLine(lines[index]).indexOutsideQuotes('=')
    }

    private fun endOf(index: Int): Position {
        val equals = equalsOf(index)
        return if (equals < 0) Position(index, lines[index].length) else ValueScan(lines, index, equals + 1).end()
    }

    /** A place in [lines]: a line and a column on it. */
    private data class Position(val row: Int, val column: Int)

    /** Where the value that starts at [row], [column] ends: on the line where every string, array and inline table it
     *  opens is closed, at that line's trailing comment or its end. */
    private class ValueScan(private val lines: List<String>, private var row: Int, private var column: Int) {
        private var depth = 0

        /** The delimiter of the string the scan is inside, or empty outside one. */
        private var quote = ""

        /** Whether a multi-line string or an array the value opened is still open at the end of the line. */
        private val open: Boolean get() = quote.length > 1 || depth > 0

        fun end(): Position {
            var stop = scanRow()
            while (open && row < lines.lastIndex) {
                row++
                column = 0
                stop = scanRow()
            }
            return Position(row, stop)
        }

        /** Steps through the rest of the current line; where its trailing comment starts, else its length. */
        private fun scanRow(): Int {
            val text = lines[row]
            while (column < text.length) {
                if (quote.isEmpty() && text[column] == '#') return column
                column = if (quote.isEmpty()) outside(text) else inside(text)
            }
            if (quote.length == 1) quote = ""
            return text.length
        }

        private fun outside(text: String): Int {
            val char = text[column]
            when (char) {
                '"', '\'' -> quote = if (text.startsWith("$char$char$char", column)) "$char$char$char" else "$char"
                '[', '{' -> depth++
                ']', '}' -> depth--
            }
            return column + quote.length.coerceAtLeast(1)
        }

        private fun inside(text: String): Int = when {
            quote[0] == '"' && text[column] == '\\' -> column + 2
            text.startsWith(quote, column) -> closed(text)
            else -> column + 1
        }

        /** Past the delimiter that closes the open string at [column], with the quotes a multi-line one may end in. */
        private fun closed(text: String): Int {
            val after = column + quote.length
            val extra = if (quote.length > 1) {
                text.drop(after).takeWhile { it == quote[0] }.length.coerceAtMost(TRAILING_QUOTES)
            } else {
                0
            }
            quote = ""
            return after + extra
        }
    }
}
