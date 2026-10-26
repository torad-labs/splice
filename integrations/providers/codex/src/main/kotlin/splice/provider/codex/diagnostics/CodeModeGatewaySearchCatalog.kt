package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.upstream.codemode.CodeModeToolSearch

/** The search probe's two catalogs: the client's tools before and after a refresh, and the deferred lite
 *  declaration GPT is sent, whose namespace reads [namespaceDescription]. */
internal class CodeModeGatewaySearchCatalog(private val namespaceDescription: String) {
    private val schema = buildJsonObject {
        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE, "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "value",
                    buildJsonObject {
                        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE, "string")
                        put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION, "Lookup value.")
                    },
                )
            },
        )
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("value")) })
    }

    fun tools(refreshed: Boolean): List<JsonObject> {
        return listOf(
            tool("Ping", "Health check"),
            tool(
                if (refreshed) "fresh.tool" else "hidden-tool",
                if (refreshed) "Quasar lookup version two" else "Quasar lookup version one",
            ),
        )
    }

    private fun tool(name: String, description: String): JsonObject = buildJsonObject {
        put(
            CALL_NAME,
            name,
        ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION, description); put("input_schema", schema)
    }

    fun declaration(eager: JsonObject): JsonObject = buildJsonObject {
        put(
            CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
            "additional_tools",
        ); put(CODE_MODE_GATEWAY_SEARCH_REQUESTS_ROLE, "developer")
        val members = listOf(
            buildJsonObject {
                put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                    "function",
                ); put(
                    CALL_NAME,
                    eager.getValue(CALL_NAME),
                ); put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION,
                    eager.getValue(CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION),
                )
                put("parameters", schema)
            },
            buildJsonObject {
                put(
                    CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE,
                    "tool_search",
                ); put("parameters", CodeModeToolSearch.inputSchema)
            },
        )
        val grouped = ResponsesFunctionNamespace().group(JsonArray(members)).map { member ->
            val item = member as JsonObject
            if (JsonScalars.str(item, CODE_MODE_GATEWAY_SEARCH_REQUESTS_TYPE) == "namespace") {
                JsonObject(
                    item + (
                        CODE_MODE_GATEWAY_SEARCH_REQUESTS_DESCRIPTION to kotlinx.serialization.json.JsonPrimitive(
                            namespaceDescription,
                        )
                        ),
                )
            } else {
                item
            }
        }
        put("tools", JsonArray(grouped))
    }
}
