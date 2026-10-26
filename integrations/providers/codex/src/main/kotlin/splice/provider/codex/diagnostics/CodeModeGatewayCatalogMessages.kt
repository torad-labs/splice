package splice.provider.codex.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.provider.codex.catalog.CodeModeModelCatalog
import splice.provider.codex.catalog.CodeModeModelMetadata

internal object CodeModeGatewayCatalogMessages : CodeModeModelCatalog {
    override fun catalog(): Map<String, CodeModeModelMetadata> =
        mapOf("fixture-search-model" to CodeModeModelMetadata(messages(), useResponsesLite = true))

    private fun messages(): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonObject {
                put(
                    "code_mode",
                    buildJsonObject {
                        put(
                            "exec",
                            buildJsonObject {
                                put(
                                    "description",
                                    "Catalog exec {{ default_exec_yield_time_ms }}\n{{ image_helper }}",
                                )
                            },
                        )
                        put("deferred_nested_tools_guidance", ""); put("mcp_typescript_preamble", "")
                        put(
                            "wait",
                            buildJsonObject {
                                put("description", "")
                                put(
                                    "parameters",
                                    """{"type":"object","properties":""" +
                                        """{"fixture_tag":{"type":"string"}},"additionalProperties":true}""",
                                )
                            },
                        )
                    },
                )
                put(
                    "functions_namespace_functions_description_prefixes",
                    buildJsonObject { put("Ping", " Catalog function prefix. ") },
                )
                put(
                    "indirect_description_prefixes",
                    buildJsonObject {
                        put("namespaces", buildJsonObject { put("functions", " Catalog namespace prefix. ") })
                    },
                )
            },
        )
    }
}
