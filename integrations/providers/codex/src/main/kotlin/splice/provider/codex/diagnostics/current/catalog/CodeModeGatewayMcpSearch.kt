package splice.provider.codex.diagnostics.current.catalog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import splice.core.util.JsonScalars
import splice.upstream.codemode.search.CodeModeMcpSearchDescriptions
import splice.upstream.codemode.search.CodeModeMcpSearchIdentity
import splice.upstream.codemode.search.CodeModeMcpSearchInfo

/** Only source-bound fixture input may supply raw MCP identities to the same production catalog port. */
internal object CodeModeGatewayMcpSearch {
    fun read(specification: JsonObject): Map<String, CodeModeMcpSearchInfo> =
        (specification["mcpSearch"] as? JsonObject).orEmpty().mapValues { (name, value) ->
            val item = value as JsonObject
            val identity = item.getValue("identity") as JsonObject
            val docs = (item["descriptions"] as? JsonObject).orEmpty()
            CodeModeMcpSearchInfo(
                CodeModeMcpSearchIdentity(
                    string(identity, "flatName"),
                    string(identity, "callableName"),
                    string(identity, "rawToolName"),
                    string(identity, "serverName"),
                ),
                CodeModeMcpSearchDescriptions(
                    JsonScalars.str(docs["title"]),
                    JsonScalars.str(docs["description"]),
                    JsonScalars.str(docs["connectorName"]),
                    JsonScalars.str(docs["namespaceDescription"]),
                    strings(docs["pluginDisplayNames"]),
                ),
                strings(item["inputPropertyNames"]),
                item["agentPlugin"] == JsonPrimitive(true),
                JsonScalars.str(item, "declarationName") ?: name,
            )
        }

    private fun string(value: JsonObject, key: String): String {
        require((value[key] as? JsonPrimitive)?.isString == true) { "MCP search fixture omitted its typed $key" }
        return checkNotNull(JsonScalars.str(value, key))
    }

    private fun strings(value: kotlinx.serialization.json.JsonElement?): List<String> =
        (value as? JsonArray).orEmpty().map {
            require((it as? JsonPrimitive)?.isString == true) { "MCP search fixture contains an untyped member" }
            checkNotNull(JsonScalars.str(it))
        }
}
