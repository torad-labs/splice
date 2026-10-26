package splice.upstream.codemode.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.CodeModeFunctionPrefixes
import splice.upstream.codemode.CodeModeManual
import splice.upstream.codemode.CodeModeNamespacePrefixes

internal object CodeModeProbePrefixes {
    fun evaluate(input: JsonObject): JsonObject = try {
        val tools = CodeModeProbeTools.loadable(input["tools"])
        val configured = strings(input["function_prefixes"] as? JsonObject)
        val functions = CodeModeFunctionPrefixes(configured, tools).apply(tools)
        val registered = (input["registered_namespaces"] as? JsonArray).orEmpty().map { value ->
            val pair = value as JsonObject
            checkNotNull(JsonScalars.str(pair, "server")) to checkNotNull(JsonScalars.str(pair, "namespace"))
        }
        val prefixes = CodeModeNamespacePrefixes(
            strings(input["namespaces"] as? JsonObject),
            strings(input["mcp_servers"] as? JsonObject),
            registered,
        )
        buildJsonObject {
            put("function_prepared", JsonArray(functions.map(CodeModeProbeTools::wire)))
            put(
                "runtime",
                JsonArray(prefixes.codeMode(CodeModeManual.prepare(functions)).map(CodeModeProbeTools::wire)),
            )
            put("prompt_description", CodeModeManual.description(prefixes.prompt(functions), false))
        }
    } catch (error: IllegalArgumentException) {
        buildJsonObject { put("error", error.message) }
    }

    private fun strings(input: JsonObject?): Map<String, String> = input.orEmpty().mapNotNull { (key, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
    }.toMap()
}
