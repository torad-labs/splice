// NEW: the `[capabilities.*]` tables of splice.toml, read apart from the rest of the file (docs/specs/harness.md,
// decision 1). The schema decoder is strict, and a capability's findings are its own: each names its capability and
// the key at fault, all of them at once, where a decode would stop at the first. So the tables are taken out of the
// text first, their lines blanked so every other line keeps its number, and read here.
package splice.core.capability

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import splice.core.topology.TomlEntry
import splice.core.topology.TomlKind
import splice.core.topology.TomlScan

/** One capability's table as written: its keys, each read as a JSON value ([JsonNull] for one that cannot be read),
 *  the line of each top-level key, and the line its table starts on. [listed] is a `[[capabilities.<name>]]`. */
public data class CapabilityTable(
    val name: String,
    val keys: JsonObject,
    val line: Int,
    val lines: Map<String, Int>,
    val listed: Boolean = false,
)

/** splice.toml with its capability tables taken out ([rest], same line count), and those tables in file order. */
public data class CapabilityText(val rest: String, val tables: List<CapabilityTable>)

public object CapabilityToml {
    /** Splits [text] into what the schema decodes and the capability tables; a file without any comes back as it is. */
    public fun split(text: String): CapabilityText {
        val entries = TomlScan(text).entries().filter { it.path.firstOrNull() == ROOT }
        if (entries.isEmpty()) return CapabilityText(text, emptyList())
        val blanked = text.toCharArray()
        val tables = LinkedHashMap<String, Building>()
        for (entry in entries) {
            blank(blanked, entry)
            val name = entry.path.getOrNull(1) ?: continue
            val line = lineOf(text, entry.start)
            val table = tables.getOrPut(name) { Building(line) }
            when (entry.kind) {
                TomlKind.ARRAY_TABLE -> table.listed = true
                TomlKind.TABLE -> {}
                TomlKind.KEY -> table.put(entry.path.drop(2), value(text, entry), line)
            }
        }
        return CapabilityText(String(blanked), tables.map { (name, b) -> b.table(name) })
    }

    /** The entry's characters as spaces, its line breaks kept, so every other line keeps its number. */
    private fun blank(chars: CharArray, entry: TomlEntry) {
        for (i in entry.start until entry.end) if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' '
    }

    private fun value(text: String, entry: TomlEntry): JsonElement =
        TomlValueRead(text.substring(entry.valueStart, entry.valueEnd)).read() ?: JsonNull

    private fun lineOf(text: String, offset: Int): Int = 1 + (0 until offset).count { text[it] == '\n' }

    /** A table while its keys are read: `[capabilities.x.when]`, `when.turn = ...` and `when = { ... }` all land in
     *  one tree, and `[capabilities] x = { ... }` gives the table its keys whole. */
    private class Building(val line: Int) {
        val tree = Branch()
        val lines = LinkedHashMap<String, Int>()
        var listed = false

        fun put(path: List<String>, value: JsonElement, at: Int) {
            if (path.isEmpty()) {
                if (value is JsonObject) {
                    value.forEach { (k, v) -> put(listOf(k), v, at) }
                } else {
                    tree.leaves[WHOLE] = value
                }
                return
            }
            lines.putIfAbsent(path.first(), at)
            var node = tree
            for (segment in path.dropLast(1)) node = node.branches.getOrPut(segment) { Branch() }
            node.leaves[path.last()] = value
        }

        fun table(name: String) = CapabilityTable(name, tree.json(), line, lines, listed)
    }

    /** A table under construction: its plain values and its sub-tables, merged when it is read whole. */
    private class Branch {
        val leaves = LinkedHashMap<String, JsonElement>()
        val branches = LinkedHashMap<String, Branch>()

        fun json(): JsonObject = JsonObject(leaves + branches.mapValues { (_, b) -> b.json() })
    }

    /** The key a table carries when splice.toml gives a capability a value that is not a table. */
    public const val WHOLE: String = "="
    private const val ROOT = "capabilities"
}
