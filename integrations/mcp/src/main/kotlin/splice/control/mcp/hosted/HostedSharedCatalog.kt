package splice.control.mcp.hosted

import kotlinx.serialization.json.JsonObject
import splice.client.mcp.McpServerSpec
import splice.control.mcp.HostedServerStore
import splice.control.mcp.McpIdentity

/** A listing may cross sessions only while its explicitly shared launch tuple still owns this alias. */
internal class HostedSharedCatalog(private val store: HostedServerStore) {
    fun read(specs: Map<String, McpServerSpec>): Map<String, JsonObject> = synchronized(store.lock) {
        specs.mapNotNull { (name, spec) ->
            val identity = McpIdentity(spec.command, spec.args, spec.env, spec.cwd)
            val server = store.servers[identity]?.takeIf { store.bindings[name] == identity }
            server?.tools?.snapshot()?.let { name to it }
        }.toMap()
    }
}
