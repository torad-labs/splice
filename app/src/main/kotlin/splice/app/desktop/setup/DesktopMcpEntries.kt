// NEW: desktop-3p-parity (Nov 23, 2026). Claude Desktop's `mcpServers` brought up to what it should have. An entry
// splice laid for the stdio bridge is known by its command, the bridge program in splice's state folder, so splice
// keeps those current at every sync: one for each server reached through the bridge, none left for a server that is
// gone. Every other entry is the person's, or Desktop's, and stays as it is; a server they have under the same name
// keeps their entry. Servers Desktop starts itself are added only where it has none by that name.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import splice.core.util.JsonScalars

internal class DesktopMcpEntries(
    /** The bridge program's absolute path, which every entry splice lays for the bridge names as its command. */
    private val bridge: String,
) {
    /** [held], Desktop's servers now (none when null), with splice's bridge entries for [bridged] and only those, and
     *  each server of [missing] it lacks. */
    fun merged(held: JsonObject?, bridged: List<String>, missing: JsonObject): JsonObject {
        val servers = LinkedHashMap<String, JsonElement>(held.orEmpty())
        servers.entries.removeIf { (name, entry) -> ours(entry) && name !in bridged }
        bridged.forEach { name ->
            if (name !in servers || ours(servers[name])) servers[name] = entry(name)
        }
        missing.forEach { (name, entry) -> servers.putIfAbsent(name, entry) }
        return JsonObject(servers)
    }

    /** Whether [entry] is one splice laid for the bridge. */
    fun ours(entry: JsonElement?): Boolean = JsonScalars.str(entry as? JsonObject, MCP_COMMAND) == bridge

    private fun entry(name: String): JsonObject = buildJsonObject {
        put(MCP_COMMAND, JsonPrimitive(bridge))
        put(MCP_ARGS, buildJsonArray { add(JsonPrimitive(name)) })
    }
}
