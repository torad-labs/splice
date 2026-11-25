// NEW: desktop-3p-parity (Nov 23, 2026). Claude Desktop's `mcpServers` brought up to what it should have. An entry
// splice laid for the stdio bridge is known by its program, splice's `desktop-mcp` bridge in whatever state folder
// splice had when it laid it, run with the server's name alone, so splice keeps those current at every sync, a stale
// one from an older state root included: one for each server reached through the bridge, none left for a server that is
// gone. Every other entry is the person's, or Desktop's, and stays as it is; a server they have under the same name
// keeps their entry. Servers Desktop starts itself are added only where it has none by that name.
package splice.app.desktop.setup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import splice.core.util.JsonScalars
import java.nio.file.InvalidPathException
import java.nio.file.Path

// The keys of an entry splice lays for the bridge.
private val BRIDGE_KEYS = setOf(MCP_COMMAND, MCP_ARGS)

internal class DesktopMcpEntries(
    /** The bridge program's absolute path, which every entry splice lays for the bridge names as its command. */
    private val bridge: String,
) {
    private val bridgeName: Path = Path.of(bridge).fileName

    /** [held], Desktop's servers now (none when null), with splice's bridge entries for [bridged] and only those, and
     *  each server of [missing] it lacks. */
    fun merged(held: JsonObject?, bridged: List<String>, missing: JsonObject): JsonObject {
        val servers = LinkedHashMap<String, JsonElement>(held.orEmpty())
        servers.entries.removeIf { (name, entry) -> ours(name, entry) && name !in bridged }
        // An entry splice laid from another state folder (an older state root) is put back as the current one.
        bridged.forEach { name ->
            if (name !in servers || ours(name, servers[name])) servers[name] = entry(name)
        }
        missing.forEach { (name, entry) -> servers.putIfAbsent(name, entry) }
        return JsonObject(servers)
    }

    /** Whether [entry], under the server [name], is one splice laid for the bridge, whatever state folder splice had
     *  when it laid it: exactly a command and its arguments, the command an absolute path to a program with the
     *  bridge's own name (`desktop-mcp`), and the arguments the server's name alone. Splice lays nothing else that way,
     *  and an entry a person writes for a server of theirs runs their own program. */
    fun ours(name: String, entry: JsonElement?): Boolean {
        val held = entry as? JsonObject ?: return false
        val program = JsonScalars.str(held, MCP_COMMAND)?.let(::absolute)
        val args = (held[MCP_ARGS] as? JsonArray)?.map { JsonScalars.str(it) }
        return held.keys == BRIDGE_KEYS && program?.fileName == bridgeName && args == listOf(name)
    }

    /** [command] as an absolute path, or null when it is not one. */
    private fun absolute(command: String): Path? = try {
        Path.of(command).takeIf { it.isAbsolute }
    } catch (_: InvalidPathException) {
        null
    }

    private fun entry(name: String): JsonObject = buildJsonObject {
        put(MCP_COMMAND, JsonPrimitive(bridge))
        put(MCP_ARGS, buildJsonArray { add(JsonPrimitive(name)) })
    }
}
