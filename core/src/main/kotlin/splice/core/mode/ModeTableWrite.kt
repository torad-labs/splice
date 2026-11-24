// NEW: splice.toml's `[modes.<name>]` table written (modes/README.md, PUT /api/modes/<name>) through the daemon's one
// splice.toml writer, so the rest of the file and its comments are kept and the write is backed up and locked as every
// edit is. A switch changes only the keys it was asked to, against the table as the file holds it now: every other key
// (`server_routes`, `server_admin_only`, one this splice does not know) stays as written, so an operator's edit made
// since the daemon last read the file is never overwritten. A table left with no key is removed.
package splice.core.mode

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.capability.CapabilityToml
import splice.core.topology.TopologyWriteResult
import splice.core.topology.TopologyWriter
import splice.core.topology.toml.TomlEmit
import splice.core.topology.toml.TomlKeys
import splice.core.topology.toml.TomlPatch
import splice.core.topology.toml.TomlTextPatch

/** What a switch changes in one `[modes.<name>]` table: `on` when [setsOn] (to [on]; null clears it), and each head in
 *  [heads] (null clears it). */
public data class ModeTableEdit(val setsOn: Boolean, val on: Boolean?, val heads: Map<String, Boolean?> = emptyMap())

public class ModeTableWrite(private val writer: TopologyWriter) {
    /** Applies [edit] to mode [name]'s table as splice.toml holds it now; one left with no key is removed. */
    public fun write(name: String, edit: ModeTableEdit): TopologyWriteResult = writer.patched(
        TomlTextPatch { text ->
            val held = CapabilityToml.split(text, MODE_TABLES).tables.associate { it.name to it.keys }
            val table = edited(held[name] ?: JsonObject(emptyMap()), edit)
            if (name !in held && table != null) {
                appended(text, name, table)
            } else {
                val wanted = held - name + listOfNotNull(table?.let { name to it })
                val tree = { tables: Map<String, JsonObject> -> JsonObject(mapOf(MODE_TABLES to JsonObject(tables))) }
                TomlPatch(text, tree(wanted)).compose(tree(held))
            }
        },
    )

    /** A table the file does not hold yet, at its end under its own `[modes.<name>]` header, `heads` inline. */
    private fun appended(text: String, name: String, table: JsonObject): String {
        val keys = TomlKeys()
        val emit = TomlEmit(keys)
        val head = "[${keys.path(listOf(MODE_TABLES, name))}]\n"
        val body = table.entries.joinToString("") { emit.line(listOf(it.key), it.value) }
        return text.trimEnd('\n') + "\n\n" + head + body
    }

    /** [keys], the table as the file holds it, with [edit] applied and nothing else touched; null once it is empty. */
    private fun edited(keys: JsonObject, edit: ModeTableEdit): JsonObject? {
        val table = LinkedHashMap<String, JsonElement>(keys)
        if (edit.setsOn) set(table, MODE_ON, edit.on)
        if (edit.heads.isNotEmpty()) {
            val heads = LinkedHashMap<String, JsonElement>((keys[MODE_HEADS] as? JsonObject).orEmpty())
            edit.heads.forEach { (head, on) -> set(heads, head, on) }
            if (heads.isEmpty()) table.remove(MODE_HEADS) else table[MODE_HEADS] = JsonObject(heads)
        }
        return table.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }

    /** [key] in [into] set to [value], or taken out for null. */
    private fun set(into: MutableMap<String, JsonElement>, key: String, value: Boolean?) {
        if (value == null) into.remove(key) else into[key] = JsonPrimitive(value)
    }
}
