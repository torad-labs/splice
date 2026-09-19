// NEW: V4-128 — the diff between what splice.toml holds and what is wanted, applied as edits to its
// text. Split from TopologyWriter.kt by concern (concentration, 2026-09-18); that file's header
// states what the edits are and why the output is verified.
package splice.core.topology

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One replacement of `text[start, end)`; start == end is an insertion. */
internal data class TomlEdit(val start: Int, val end: Int, val text: String)

/** The diff between what the file holds and what is wanted, applied as edits to the file's text. */
internal class TomlPatch(private val text: String, private val wanted: JsonObject) {
    private val keys = TomlKeys()
    private val emit = TomlEmit(keys)
    private val entries = TomlScan(text).entries()
    private val edits = mutableListOf<TomlEdit>()
    private val tail = StringBuilder()

    fun compose(held: JsonObject): String {
        diff(emptyList(), held, wanted)
        return apply()
    }

    private fun diff(path: List<String>, held: JsonObject, want: JsonObject) {
        for (key in held.keys + want.keys) {
            val at = path + key
            val old = held[key]
            val new = want[key]
            // A table the file spells out but whose every value is a default is absent from the
            // held tree; it is still in the text, so it is descended into rather than appended twice.
            val spelled = old == null && entries.any { keys.isPrefix(at, it.path) }
            when {
                old == new -> Unit
                old is JsonObject && new is JsonObject -> diff(at, old, new)
                new is JsonObject && spelled -> diff(at, JsonObject(emptyMap()), new)
                else -> change(at, new)
            }
        }
    }

    private fun change(path: List<String>, new: JsonElement?) {
        val line = entries.firstOrNull { it.kind == TomlKind.KEY && keys.isPrefix(it.path, path) }
        when {
            line != null -> rewrite(line)
            new == null -> remove(path)
            else -> add(path, new)
        }
    }

    /** The whole value on [line], from the wanted tree at the line's own path. */
    private fun rewrite(line: TomlEntry) {
        val value = keys.at(wanted, line.path)
        edits += if (value == null) {
            TomlEdit(line.start, line.end, "")
        } else {
            TomlEdit(line.valueStart, line.valueEnd, emit.inline(value))
        }
    }

    /** Every statement under [path]. A section's range runs on to the next statement when that one is
     *  under [path] too, so a group of sections leaves as one block and not as a trail of blank lines. */
    private fun remove(path: List<String>) {
        entries.forEachIndexed { index, entry ->
            if (keys.isPrefix(path, entry.path)) {
                val end = if (entry.kind == TomlKind.KEY) entry.end else sectionEnd(index)
                val next = entries.firstOrNull { it.start >= end }
                val joined = next != null && keys.isPrefix(path, next.path)
                edits += TomlEdit(entry.start, if (joined) checkNotNull(next).start else end, "")
            }
        }
    }

    private fun add(path: List<String>, new: JsonElement) {
        val first = entries.firstOrNull { it.kind == TomlKind.ARRAY_TABLE && it.path == path }
        when {
            first != null -> replaceArrayTables(first, path, new)
            new is JsonObject -> tail.append("\n").append(emit.table(path, new))
            else -> insert(path, new)
        }
    }

    private fun replaceArrayTables(first: TomlEntry, path: List<String>, new: JsonElement) {
        remove(path)
        val array = new as? JsonArray ?: JsonArray(emptyList())
        val elements = array.filterIsInstance<JsonObject>()
        val tables = elements.isNotEmpty() && elements.size == array.size
        if (tables) edits += TomlEdit(first.start, first.start, emit.arrayTables(path, elements)) else insert(path, new)
    }

    /** A new key line in its table, or beside its dotted siblings, or in a new table at the end. */
    private fun insert(path: List<String>, value: JsonElement) {
        val parent = path.dropLast(1)
        val table = entries.indexOfFirst { it.kind == TomlKind.TABLE && it.path == parent }
        val sibling = entries.lastOrNull { dottedUnder(it, parent) }
        when {
            table >= 0 -> at(sectionEnd(table), emit.line(listOf(path.last()), value))
            sibling != null -> at(sibling.end, emit.line(path.drop(sibling.tableDepth), value))
            else -> tail.append("\n[${keys.path(parent)}]\n").append(emit.line(listOf(path.last()), value))
        }
    }

    /** A key that spells [parent] with dotted keys from an ancestor's table (`quirks.store = true`
     *  under `[providers.x]`): a new sibling goes beside it, because a `[parent]` header appended
     *  later would redefine a table the dotted keys already defined. */
    private fun dottedUnder(entry: TomlEntry, parent: List<String>): Boolean {
        val ancestorTable = entry.kind == TomlKind.KEY && entry.tableDepth <= parent.size
        return ancestorTable && keys.isPrefix(parent, entry.path.dropLast(1))
    }

    private fun at(position: Int, line: String) {
        val glued = position == text.length && !text.endsWith("\n")
        edits += TomlEdit(position, position, if (glued) "\n$line" else line)
    }

    /** Where a table's own statements end: after its last key, before the next header. */
    private fun sectionEnd(header: Int): Int =
        entries.drop(header + 1).takeWhile { it.kind == TomlKind.KEY }.lastOrNull()?.end ?: entries[header].end

    /** Edits in text order; one inside a range an earlier edit already replaced is dropped with it. */
    private fun apply(): String {
        val out = StringBuilder()
        var cursor = 0
        for (edit in edits.distinct().sortedWith(compareBy(TomlEdit::start, TomlEdit::end))) {
            if (edit.start < cursor) continue
            out.append(text, cursor, edit.start).append(edit.text)
            cursor = maxOf(cursor, edit.end)
        }
        out.append(text, cursor, text.length)
        val open = out.isNotEmpty() && !out.endsWith("\n")
        if (tail.isNotEmpty() && open) out.append("\n")
        return out.append(tail).toString()
    }
}
