// NEW: the tool_search_output wire shape. Split from ResponsesToolSearch.kt
// so the per-turn controller is not billed for the output builder
// (concentration, 2026-08-19). Same-package — both in-turn answering and
// declaration-replay keep splice.dialect.responses.ToolSearchOutput.
package splice.dialect.responses

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.wire.ToolDefinition

/**
 * The tool_search_output shape (type, call_id, status, execution, tools[]) — the ONE builder for
 * both callers: the within-turn answer ([ResponsesToolSearchController.continuationForSearch])
 * and the declaration-replay injection (ResponsesRequestBuilder.kt CHANGE 2, cache-prefix
 * stability 2026-07-25) that re-declares a deferred tool's schema in history before its
 * function_call. Never re-authored as a second shape — see this file's header and ToolSurface.kt's.
 * A type rather than a file-level function (Kotlin main sources carry no top-level functions); the
 * member keeps its old name and argument list.
 */
internal class ToolSearchOutput {

    private val toolWire = ToolWireObjects()

    fun toolSearchOutputItem(
        callId: String,
        tools: List<ToolDefinition>,
        emitStrict: Boolean,
        forceStrictFalse: Boolean,
        normalizeSchemas: Boolean,
    ): JsonObject = buildJsonObject {
        put(FIELD_TYPE, TYPE_TOOL_SEARCH_OUTPUT)
        put(FIELD_CALL_ID, callId)
        put(FIELD_STATUS, STATUS_COMPLETED)
        put(FIELD_EXECUTION, EXECUTION_CLIENT)
        put(
            FIELD_TOOLS,
            buildJsonArray {
                tools.forEach { add(toolWire.deferredToolObject(it, emitStrict, forceStrictFalse, normalizeSchemas)) }
            },
        )
    }
}

private const val FIELD_TYPE = "type"
private const val FIELD_CALL_ID = "call_id"
private const val FIELD_STATUS = "status"
private const val FIELD_EXECUTION = "execution"
private const val FIELD_TOOLS = "tools"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
private const val STATUS_COMPLETED = "completed"
private const val EXECUTION_CLIENT = "client"
