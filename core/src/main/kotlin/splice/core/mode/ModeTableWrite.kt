// NEW: splice.toml's `[modes.<name>]` table written (modes/README.md, PUT /api/modes/<name>) through the daemon's one
// splice.toml writer, so the rest of the file and its comments are kept and the write is backed up and locked as every
// edit is. A table left with no key is removed.
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

public class ModeTableWrite(private val writer: TopologyWriter) {
    /** Writes mode [name]'s table as [setting]; one with no key removes the table. */
    public fun write(name: String, setting: ModeSetting): TopologyWriteResult = writer.patched(
        TomlTextPatch { text ->
            val held = CapabilityToml.split(text, MODE_TABLES).tables.associate { it.name to it.keys }
            val table = table(setting)
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

    private fun table(setting: ModeSetting): JsonObject? {
        val keys = LinkedHashMap<String, JsonElement>()
        setting.on?.let { keys[MODE_ON] = JsonPrimitive(it) }
        if (setting.heads.isNotEmpty()) {
            keys[MODE_HEADS] = JsonObject(setting.heads.mapValues { JsonPrimitive(it.value) })
        }
        setting.serverRoutes?.let { keys[MODE_SERVER_ROUTES] = JsonPrimitive(it) }
        setting.serverAdminOnly?.let { keys[MODE_SERVER_ADMIN_ONLY] = JsonPrimitive(it) }
        return keys.takeIf { it.isNotEmpty() }?.let(::JsonObject)
    }
}
