// NEW: the `worker` table of a capability (docs/specs/harness.md, decisions 1, 9 and 13), read for CapabilityDeclarations
// with every wrong key or value a finding of its own. A finding names a value only when it is a name: a url can hold a
// secret.
package splice.core.capability

import kotlinx.serialization.json.JsonObject

/** The `worker` table: an http worker with its url, a tool of an MCP server, or a built-in worker splice ships. */
internal class CapabilityWorkerCheck(private val values: CapabilityValues, private val builtins: Set<String>) {
    fun read(): CapabilityWorker? {
        val keys = values.table.keys["worker"] as? JsonObject
        if (keys == null) {
            values.fault(
                "worker",
                "is required: { kind = \"http\", url = \"...\" }, " +
                    "{ kind = \"mcp\", server = \"...\", tool = \"...\" } or { kind = \"builtin\", name = \"...\" }",
            )
            return null
        }
        return when (values.string(keys["kind"])) {
            HTTP -> http(keys)
            MCP -> mcp(keys)
            BUILTIN -> builtin(keys)
            else -> null.also { values.fault("worker.kind", "must be http, mcp or builtin") }
        }
    }

    private fun http(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in HTTP_KEYS }.forEach { values.fault("worker.$it", "is not a key an http worker has") }
        val url = values.string(keys["url"])?.takeIf { it.isNotBlank() }
        val authEnv = keys["auth_env"]?.let { values.string(it)?.takeIf(String::isNotBlank) ?: "" }
        if (authEnv == "") values.fault("worker.auth_env", "must name an environment variable")
        if (url == null) values.fault("worker.url", "is required for an http worker")
        return url?.let { CapabilityWorker.Http(it, authEnv) }
    }

    private fun mcp(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in MCP_KEYS }.forEach { values.fault("worker.$it", "is not a key an MCP worker has") }
        val server = values.string(keys["server"])?.takeIf { it.isNotBlank() }
        val tool = values.string(keys["tool"])?.takeIf { it.isNotBlank() }
        if (server == null) values.fault("worker.server", "is required for an MCP worker")
        if (tool == null) values.fault("worker.tool", "is required for an MCP worker")
        return if (server != null && tool != null) CapabilityWorker.Mcp(server, tool) else null
    }

    private fun builtin(keys: JsonObject): CapabilityWorker? {
        keys.keys.filter { it !in BUILTIN_KEYS }.forEach {
            values.fault(
                "worker.$it",
                "is not a key a built-in worker has",
            )
        }
        val name = values.string(keys["name"])
        when {
            name == null -> values.fault("worker.name", "is required for a built-in worker")
            name !in builtins -> values.fault(
                "worker.name",
                "names built-in worker \"${values.safe(name)}\", which splice does not ship",
            )
        }
        // `model`: the model a built-in worker runs on, where it runs one; absent is its own default.
        val model = keys["model"]?.let { values.string(it)?.takeIf(String::isNotBlank) ?: "" }
        if (model == "") values.fault("worker.model", "must name a model id")
        return name?.takeIf { it in builtins && model != "" }?.let { CapabilityWorker.Builtin(it, model) }
    }
}

private val HTTP_KEYS = setOf("kind", "url", "auth_env")
private val BUILTIN_KEYS = setOf("kind", "name", "model")
private val MCP_KEYS = setOf("kind", "server", "tool")
private const val HTTP = "http"
private const val MCP = "mcp"
private const val BUILTIN = "builtin"
