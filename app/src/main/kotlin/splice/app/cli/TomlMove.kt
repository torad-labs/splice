// NEW: gateway-no-heads. The text edit behind the fix session's move: a value taken off its line in one table and put as
// `key = value` in another. The place is the one the file already uses: the table's own header, else the inline table
// (or the dotted keys) its parent assigns it as, else a new table at the end of the file. A table the file defines one
// way is never defined a second, which TOML refuses, and a line this cannot place with certainty is left untouched:
// the answer is null, and nothing is written. Unrelated fields and every other byte of the file stay as they were.
package splice.app.cli

internal class TomlMove(private val lines: List<String>) {
    /** How the file defines a table whose parent it assigns it in: as an inline table, as dotted keys, or as something
     *  else (a scalar, which nothing can be added to). */
    private enum class Kind { INLINE, DOTTED, OTHER }

    /** The assignment at [index] of the table, the end of its section, and the name as the file spells it. */
    private data class Definition(val index: Int, val end: Int, val kind: Kind, val name: String)

    /** The text after the `=` of the assignment on [line] (1-based), when there is one. */
    fun valueAt(line: Int?): String? = line?.let { lines.getOrNull(it - 1) }
        ?.substringAfter('=', "")?.trim()?.takeIf(String::isNotEmpty)

    /** The line (1-based) that assigns the bare [key] in the same section as [line], the table header above it down to
     *  the next, or null when that section assigns it on no line of its own. */
    fun lineOf(key: String, line: Int): Int? {
        val at = line - 1
        if (at !in lines.indices) return null
        val start = (at downTo 0).firstOrNull { lines[it].trimStart().startsWith("[") } ?: -1
        val end = (at + 1 until lines.size).firstOrNull { lines[it].trimStart().startsWith("[") } ?: lines.size
        return (start + 1 until end).firstOrNull { index ->
            val text = lines[index]
            val equals = TomlLine(text).indexOutsideQuotes('=')
            val names = if (equals > 0) TomlLine(text.substring(0, equals)).dotted() else emptyList()
            !text.trimStart().startsWith("#") && names == listOf(key)
        }?.plus(1)
    }

    /** [lines] with each of [entries] (`key = value`) in [table] (a dotted path as TOML spells it), in order, and the
     *  line numbers [removed] (1-based) taken out; null when the file defines [table] in a way this cannot add to with
     *  certainty. */
    fun of(table: String, entries: List<String>, removed: Set<Int>): List<String>? {
        val kept = lines.filterIndexed { index, _ -> index + 1 !in removed }
        val path = TomlLine(table).dotted()
        val header = headerAt(kept, path)
        return if (header >= 0) {
            kept.take(header + 1) + entries + kept.drop(header + 1)
        } else {
            placed(kept, path, table, entries)
        }
    }

    /** [entries] where the file defines the table [path] other than by its own header. */
    private fun placed(from: List<String>, path: List<String>, table: String, entries: List<String>): List<String>? {
        val found = definitionOf(from, path) ?: return from + listOf("", "[$table]") + entries
        return when (found.kind) {
            Kind.INLINE -> inlined(from, found.index, entries.joinToString(", "))
            Kind.DOTTED -> dotted(from, found, path.last(), entries)
            Kind.OTHER -> null
        }
    }

    /** The line of the header `[path]`, however it is spelled (spacing, quoting), or -1. */
    private fun headerAt(from: List<String>, path: List<String>): Int = from.indexOfFirst { line ->
        val text = line.trim()
        val close = TomlLine(text).indexOutsideQuotes(']')
        val table = text.startsWith("[") && !text.startsWith("[[")
        table && close > 0 && TomlLine(text.substring(1, close)).dotted() == path
    }

    /** How the parent's section assigns the table [path] names, or null when it does not (the table is then new). */
    private fun definitionOf(from: List<String>, path: List<String>): Definition? {
        val parent = path.dropLast(1)
        val start = if (parent.isEmpty()) -1 else headerAt(from, parent)
        if (parent.isNotEmpty() && start < 0) return null
        val end = (start + 1 until from.size).firstOrNull { from[it].trimStart().startsWith("[") } ?: from.size
        return (start + 1 until end).firstNotNullOfOrNull { index -> assignment(from[index], index, end, path.last()) }
    }

    /** [line] as a definition of [leaf], when it assigns that name (alone, or as the head of a dotted key). */
    private fun assignment(line: String, index: Int, end: Int, leaf: String): Definition? {
        val equals = TomlLine(line).indexOutsideQuotes('=')
        if (equals < 0 || line.trimStart().startsWith("#")) return null
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

    /** [entry] added inside the inline table the line at [index] assigns, with its other fields and comment kept. */
    private fun inlined(from: List<String>, index: Int, entry: String): List<String>? {
        val line = from[index]
        val scan = TomlLine(line)
        val open = scan.indexOutsideQuotes('{', scan.indexOutsideQuotes('='))
        val close = if (open < 0) -1 else scan.closingBrace(open)
        if (close < 0) return null
        val fields = line.substring(open + 1, close).trim().trimEnd(',').trim()
        val joined = if (fields.isEmpty()) entry else "$fields, $entry"
        val edited = line.substring(0, open + 1) + " $joined " + line.substring(close)
        return from.take(index) + edited + from.drop(index + 1)
    }

    /** `name.<entry>` for each of [entries], added after the last line of the section that spells the table as dotted
     *  keys. */
    private fun dotted(from: List<String>, table: Definition, leaf: String, entries: List<String>): List<String> {
        val last = (table.index until table.end).last { index ->
            TomlLine(from[index].substringBefore('=')).dotted().first() == leaf && '=' in from[index]
        }
        return from.take(last + 1) + entries.map { "${table.name}.$it" } + from.drop(last + 1)
    }
}
