// NEW: a whole TOML file read as JSON, for a file splice reads apart from splice.toml's schema: a mode's mode.toml
// (modes/README.md), which the catalog checks key by key, every finding at once. Built on the scanner the topology
// writer edits with (TomlScan) and the value reader the capability tables use (TomlValueRead), so :core stays
// ktoml-free and a mode's manifest reads the way a capability's table does.
package splice.core.capability

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.topology.toml.TomlKind
import splice.core.topology.toml.TomlScan

private const val NOT_TOML = "not TOML this reader knows"

public object TomlTree {
    /** [text] as one JSON object: a `[table]` an object, a `[[table]]` an array of objects, each value as TOML wrote
     *  it. Null when the text is not TOML this reader knows: a line that is neither a header nor `key = value`, a value
     *  it cannot read, or a key given twice. The text itself is never repeated. */
    public fun read(text: String): JsonObject? = try {
        TreeBuilder(text).build()
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class TreeBuilder(private val text: String) {
    private val root = TreeNode()
    private var table: TreeNode = root

    fun build(): JsonObject {
        for (entry in TomlScan(text).entries()) {
            val stated = text.substring(entry.start, entry.end).substringBefore('#').trim()
            when (entry.kind) {
                TomlKind.TABLE -> {
                    require(stated.endsWith("]") && !stated.endsWith("]]")) { NOT_TOML }
                    table = root.table(entry.path)
                }
                TomlKind.ARRAY_TABLE -> {
                    require(stated.endsWith("]]")) { NOT_TOML }
                    table = root.appended(entry.path)
                }
                TomlKind.KEY -> {
                    require('=' in text.substring(entry.start, entry.valueStart)) { NOT_TOML }
                    val value = TomlValueRead(text.substring(entry.valueStart, entry.valueEnd)).read()
                    table.put(entry.path.drop(entry.tableDepth), requireNotNull(value) { NOT_TOML })
                }
            }
        }
        return root.json()
    }
}

/** What one key of a table holds while the file is read. */
private sealed class TreeSlot {
    class Value(val value: JsonElement) : TreeSlot()

    class Table(val node: TreeNode) : TreeSlot()

    class Tables(val nodes: MutableList<TreeNode>) : TreeSlot()
}

/** A table while it is read: its keys in the order they came. */
private class TreeNode {
    private val slots = LinkedHashMap<String, TreeSlot>()

    fun table(path: List<String>): TreeNode = path.fold(this) { node, key -> node.child(key) }

    fun appended(path: List<String>): TreeNode {
        val parent = table(path.dropLast(1))
        val slot = parent.slots.getOrPut(path.last()) { TreeSlot.Tables(mutableListOf()) }
        require(slot is TreeSlot.Tables) { NOT_TOML }
        return TreeNode().also { slot.nodes.add(it) }
    }

    fun put(path: List<String>, value: JsonElement) {
        val node = table(path.dropLast(1))
        require(path.last() !in node.slots) { NOT_TOML }
        node.slots[path.last()] = TreeSlot.Value(value)
    }

    private fun child(key: String): TreeNode = when (val slot = slots.getOrPut(key) { TreeSlot.Table(TreeNode()) }) {
        is TreeSlot.Table -> slot.node
        is TreeSlot.Tables -> slot.nodes.last()
        is TreeSlot.Value -> throw IllegalArgumentException(NOT_TOML)
    }

    fun json(): JsonObject = JsonObject(
        slots.mapValues { (_, slot) ->
            when (slot) {
                is TreeSlot.Value -> slot.value
                is TreeSlot.Table -> slot.node.json()
                is TreeSlot.Tables -> JsonArray(slot.nodes.map { it.json() })
            }
        },
    )
}
