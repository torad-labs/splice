// PORT-OF: codex-rs/tools/src/code_mode.rs and core/src/tools/handlers/tool_search_spec.rs @ rust-v0.164.0.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Schemas and guidance for the function form used by nested ranked discovery. */
public object CodeModeToolSearch {
    public const val NAME: String = splice.upstream.ToolSearchWire.NAME

    // why: Codex rust-v0.164.0 tools/src/tool_discovery.rs:7 sets TOOL_SEARCH_DEFAULT_LIMIT = 8.
    public const val DEFAULT_LIMIT: Int = 8
    public const val GUIDANCE: String =
        "Use `await tools.tool_search({query: \"...\", limit: 8})` for BM25-ranked discovery of deferred tools " +
            "(up to `limit` matches; 8 by default). It returns `{tools: [{name, description}]}` with callable names " +
            "and full TypeScript declarations. Print results with `text()` and inspect unfamiliar declarations " +
            "before calling `tools[result.name](arguments)` in a later execution."

    public val inputSchema: JsonObject = buildJsonObject {
        put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_OBJECT)
        putJsonObject(CODE_MODE_TOOL_SEARCH_PROPERTIES) {
            putJsonObject("limit") {
                put(CODE_MODE_TOOL_SEARCH_TYPE, "number")
                put(CODE_MODE_TOOL_SEARCH_DESCRIPTION, "Maximum number of tools to return. Defaults to 8.")
            }
            putJsonObject("query") {
                put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_STRING)
                put(CODE_MODE_TOOL_SEARCH_DESCRIPTION, "Search query for deferred tools.")
            }
        }
        put(CODE_MODE_TOOL_SEARCH_REQUIRED, buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("query")) })
        put("additionalProperties", false)
    }

    public val outputSchema: JsonObject = buildJsonObject {
        put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_OBJECT)
        putJsonObject(CODE_MODE_TOOL_SEARCH_PROPERTIES) {
            putJsonObject("tools") {
                put(CODE_MODE_TOOL_SEARCH_TYPE, "array")
                putJsonObject("items") {
                    put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_OBJECT)
                    putJsonObject(CODE_MODE_TOOL_SEARCH_PROPERTIES) {
                        putJsonObject("name") { put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_STRING) }
                        putJsonObject(
                            CODE_MODE_TOOL_SEARCH_DESCRIPTION,
                        ) { put(CODE_MODE_TOOL_SEARCH_TYPE, CODE_MODE_TOOL_SEARCH_STRING) }
                    }
                    put(
                        CODE_MODE_TOOL_SEARCH_REQUIRED,
                        buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("name"))
                            add(kotlinx.serialization.json.JsonPrimitive(CODE_MODE_TOOL_SEARCH_DESCRIPTION))
                        },
                    )
                }
            }
        }
        put(CODE_MODE_TOOL_SEARCH_REQUIRED, buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("tools")) })
    }

    public fun definition(parameters: JsonObject? = null): CodeModeManual.NestedTool = CodeModeManual.NestedTool(
        NAME,
        GUIDANCE,
        parameters ?: inputSchema,
        outputSchema,
    )
}

private const val CODE_MODE_TOOL_SEARCH_TYPE = "type"
private const val CODE_MODE_TOOL_SEARCH_OBJECT = "object"
private const val CODE_MODE_TOOL_SEARCH_PROPERTIES = "properties"
private const val CODE_MODE_TOOL_SEARCH_DESCRIPTION = "description"
private const val CODE_MODE_TOOL_SEARCH_STRING = "string"
private const val CODE_MODE_TOOL_SEARCH_REQUIRED = "required"
