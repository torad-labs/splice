// NEW: the wire tool objects this dialect emits. Split from ToolSurface.kt
// so the partitioner is not billed for the JSON builders (concentration, 2026-08-19).
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.ToolDefinition

/**
 * The wire tool objects this dialect emits. A type rather than the file-level functions it used to
 * be (Kotlin main sources carry no top-level functions); every member keeps its old name and
 * argument list, so a call site only gained a receiver.
 */
internal class ToolWireObjects {

    /** [forceStrictFalse] (codex-rs parity: hard-sets `strict:false` on every function tool,
     *  responses_api.rs:29-32) and [emitStrict] (pass through a tool's own strict==true) are
     *  DISTINCT quirks, not one flag with two meanings (review 2026-07-24 round 2/3): GrokProvider's
     *  pre-existing `emitStrict = true` predates this feature and was never consequential (Claude
     *  Code's ToolDefinition.strict is always null), so folding the codex-only forced-false behavior
     *  into `emitStrict` silently changed grok's live wire bytes too — a head this feature must not
     *  touch. forceStrictFalse defaults false and only CodexProvider sets it. */
    fun functionToolObject(t: ToolDefinition, emitStrict: Boolean, forceStrictFalse: Boolean): JsonObject =
        buildJsonObject {
            put(FIELD_TYPE, TYPE_FUNCTION)
            put(FIELD_NAME, t.name)
            put(FIELD_DESCRIPTION, t.description ?: "")
            put(FIELD_PARAMETERS, t.inputSchema ?: emptyObjectSchema())
            when {
                // forceStrictFalse is a HARD SET (codex-rs parity, responses_api.rs:29-32): every
                // function tool gets strict:false regardless of the tool's OWN value — passing
                // t.strict through here (review 2026-07-25) would send strict:true for a tool that
                // arrives with strict==true, breaking the exact parity this quirk exists for.
                forceStrictFalse -> put(FIELD_STRICT, false)
                emitStrict && t.strict == true -> put(FIELD_STRICT, true)
                else -> Unit
            }
        }

    /** A deferred tool as it rides inside a tool_search_output.tools[] answer — carries the same
     *  fields as [functionToolObject] plus `defer_loading:true` (tool_search.rs:36-40). Authored
     *  standalone rather than composed from [functionToolObject]: JSON key order is semantically
     *  irrelevant to the API, but composing would put defer_loading last instead of adjacent to the
     *  other declared-shape fields, and this way the two builders stay independently readable. */
    fun deferredToolObject(t: ToolDefinition, emitStrict: Boolean, forceStrictFalse: Boolean): JsonObject =
        buildJsonObject {
            put(FIELD_TYPE, TYPE_FUNCTION)
            put(FIELD_NAME, t.name)
            put(FIELD_DESCRIPTION, t.description ?: "")
            put(FIELD_DEFER_LOADING, true)
            put(FIELD_PARAMETERS, t.inputSchema ?: emptyObjectSchema())
            when {
                // Same hard-set law as functionToolObject's identical branch just above (review
                // 2026-07-25) — a deferred tool answered through tool_search gets forced strict:false
                // too, never a pass-through of its own strict==true.
                forceStrictFalse -> put(FIELD_STRICT, false)
                emitStrict && t.strict == true -> put(FIELD_STRICT, true)
                else -> Unit
            }
        }

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put(FIELD_PROPERTIES, buildJsonObject { })
    }

    /** Shape verbatim from codex core/src/tools/handlers/tool_search_spec.rs:63-76 (the
     *  ToolSearchSourceListing::Omit branch — splice has no MCP-server description metadata to
     *  list), MINUS the "with BM25" clause codex's copy carries: splice's ranking is a deterministic
     *  field-weighted substring score, not BM25 (spec §2.3; ToolSearchIndex's own header). Dropping
     *  that clause is deliberate, not a missed port. execution:"client" is what tells the backend WE
     *  answer the search, never Claude Code. [limit] is the operator's configured policy.searchLimit
     *  (threaded from the caller) so the advertised default matches what [ResponsesToolSearchController]
     *  actually clamps to (review 2026-07-24: this used to hardcode DEFAULT_SEARCH_LIMIT). */
    fun toolSearchToolObject(limit: Int): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_TOOL_SEARCH)
        put(FIELD_EXECUTION, EXECUTION_CLIENT)
        put(FIELD_DESCRIPTION, TOOL_SEARCH_DESCRIPTION)
        put(
            FIELD_PARAMETERS,
            buildJsonObject {
                put("type", "object")
                put(
                    FIELD_PROPERTIES,
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put(FIELD_DESCRIPTION, "Search query for deferred tools.")
                            },
                        )
                        put(
                            "limit",
                            buildJsonObject {
                                put("type", "number")
                                put(FIELD_DESCRIPTION, "Maximum number of tools to return. Defaults to $limit.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
                put("additionalProperties", false)
            },
        )
    }

    /** The additional_tools array for this request: every eager tool, then (only when deferring) the
     *  tool_search tool. The deferred tools themselves never ride here (search_tool.rs:723-741). */
    fun toolsSection(
        partition: ToolPartition,
        emitStrict: Boolean,
        forceStrictFalse: Boolean,
        searchLimit: Int,
    ): JsonArray = buildJsonArray {
        partition.eager.forEach { add(functionToolObject(it, emitStrict, forceStrictFalse)) }
        if (partition.deferring) add(toolSearchToolObject(searchLimit))
    }
}

// Wire field/type literals — private-per-file since StringLiteralDuplication scopes per file.
private const val FIELD_TYPE = "type"
private const val FIELD_NAME = "name"
private const val FIELD_DESCRIPTION = "description"
private const val FIELD_PARAMETERS = "parameters"
private const val FIELD_PROPERTIES = "properties"
private const val FIELD_STRICT = "strict"
private const val FIELD_DEFER_LOADING = "defer_loading"
private const val FIELD_EXECUTION = "execution"
private const val TYPE_FUNCTION = "function"
private const val TYPE_TOOL_SEARCH = "tool_search"
private const val EXECUTION_CLIENT = "client"

private const val TOOL_SEARCH_DESC_HEAD = "# Tool discovery\n\n" +
    "Searches over deferred tool metadata and exposes matching tools for the next model call.\n\n"
private const val TOOL_SEARCH_DESC_TAIL = "Some of the tools may not have been provided to you upfront, " +
    "and you should use this tool (`tool_search`) to search for the required tools. " +
    "For MCP tool discovery, always use `tool_search` instead of `list_mcp_resources` " +
    "or `list_mcp_resource_templates`."
private const val TOOL_SEARCH_DESCRIPTION = TOOL_SEARCH_DESC_HEAD + TOOL_SEARCH_DESC_TAIL
