// NEW: separates native Responses replay items from code mode's logical client history.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars
import splice.dialect.responses.tools.ToolSearchOutput

/** Separates Responses-only replay items from the client-visible conversation used by code mode. */
public class ResponsesCodeModeProjection {
    /** Index of the logical function callback used by callback-scoped replay relocation. */
    public fun functionCallIndex(items: List<JsonElement>, callbackId: String): Int =
        items.indexOfFirst { item ->
            elementType(item) == TYPE_FUNCTION_CALL && elementCallId(item) == callbackId
        }

    public fun project(input: JsonArray): ResponsesCodeModeInput {
        val logical = mutableListOf<JsonElement>()
        val replay = mutableListOf<ResponsesCodeModeReplay>()
        val pending = mutableListOf<JsonElement>()
        var index = 0
        while (index < input.size) {
            val pair = searchPair(input, index)
            when {
                pair?.declaration == true -> {
                    pending += pair.items
                    index += pair.items.size
                }
                pair != null -> {
                    flush(replay, logical.size, null, pending + pair.items)
                    pending.clear()
                    index += pair.items.size
                }
                elementType(input[index]) == TYPE_REASONING -> {
                    pending += input[index]
                    index++
                }
                elementType(input[index]) == TYPE_FUNCTION_CALL -> {
                    val callbackId = elementCallId(input[index])
                    flush(replay, logical.size, callbackId.ifEmpty { null }, pending)
                    pending.clear()
                    logical += input[index]
                    index++
                }
                else -> {
                    flush(replay, logical.size, null, pending)
                    pending.clear()
                    logical += input[index]
                    index++
                }
            }
        }
        flush(replay, logical.size, null, pending)
        return ResponsesCodeModeInput(logical, replay)
    }

    public fun rebuild(input: ResponsesCodeModeInput): JsonArray {
        val byOffset = input.replayItems.groupBy(ResponsesCodeModeReplay::logicalOffset)
            .mapValues { (_, items) -> items.sortedBy { if (it.callbackId == null) NATIVE_ORDER else ALIAS_ORDER } }
        return JsonArray(
            buildList {
                input.logicalItems.forEachIndexed { index, item ->
                    byOffset[index].orEmpty().forEach { addAll(it.items) }
                    add(item)
                }
                byOffset[input.logicalItems.size].orEmpty().forEach { addAll(it.items) }
            },
        )
    }

    private fun searchPair(input: JsonArray, index: Int): SearchPair? {
        val call = input[index] as? JsonObject ?: return null
        val output = input.getOrNull(index + 1) as? JsonObject
        val matchingPair = objectType(call) == TYPE_TOOL_SEARCH_CALL &&
            objectType(output) == TYPE_TOOL_SEARCH_OUTPUT && objectCallId(call) == objectCallId(output)
        return if (matchingPair) {
            SearchPair(
                items = listOf(call, checkNotNull(output)),
                declaration = declaresNextFunction(input, index, output),
            )
        } else {
            null
        }
    }

    private fun declaresNextFunction(input: JsonArray, pairIndex: Int, output: JsonObject): Boolean {
        val call = input[pairIndex] as? JsonObject ?: return false
        if (!isClientDeclaration(call, output)) return false
        val next = input.drop(pairIndex + SEARCH_PAIR_SIZE).dropWhile { elementType(it) == TYPE_REASONING }
            .firstOrNull() as? JsonObject
        return matchesDeclaredFunction(next, output)
    }

    private fun isClientDeclaration(call: JsonObject, output: JsonObject): Boolean =
        objectCallId(call).startsWith(TOOL_DECLARATION_ID_PREFIX) &&
            JsonScalars.str(call, ToolSearchOutput.WireFields.FIELD_EXECUTION) ==
            ToolSearchOutput.WireFields.EXECUTION_CLIENT &&
            JsonScalars.str(output, ToolSearchOutput.WireFields.FIELD_EXECUTION) ==
            ToolSearchOutput.WireFields.EXECUTION_CLIENT

    private fun matchesDeclaredFunction(next: JsonObject?, output: JsonObject): Boolean {
        if (objectType(next) != TYPE_FUNCTION_CALL) return false
        val functionName = JsonScalars.str(next, FIELD_NAME).orEmpty()
        val tools = output[FIELD_TOOLS] as? JsonArray ?: return false
        return functionName.isNotEmpty() && functionName in declaredFunctionNames(tools)
    }

    /** Deferred schemas normally arrive flat, but Responses namespace groups are also legal in a
     *  tool_search_output. Keep both the qualified and leaf spellings: function_call names use the
     *  leaf for the functions namespace and qualified names for other namespaces. */
    private fun declaredFunctionNames(tools: JsonArray): Set<String> = buildSet {
        fun collect(entries: JsonArray, namespace: String?) {
            entries.forEach { entry ->
                val tool = entry as? JsonObject ?: return@forEach
                val name = JsonScalars.str(tool, FIELD_NAME).orEmpty()
                val nested = tool[FIELD_TOOLS] as? JsonArray
                if (nested != null) {
                    collect(nested, name.takeIf { it.isNotEmpty() } ?: namespace)
                } else if (name.isNotEmpty()) {
                    add(name)
                    if (!namespace.isNullOrEmpty()) add("$namespace.$name")
                }
            }
        }
        collect(tools, null)
    }

    private fun flush(
        target: MutableList<ResponsesCodeModeReplay>,
        logicalOffset: Int,
        callbackId: String?,
        items: List<JsonElement>,
    ) {
        if (items.isNotEmpty()) target += ResponsesCodeModeReplay(logicalOffset, callbackId, items.toList())
    }

    private fun elementType(element: JsonElement): String = objectType(element as? JsonObject)

    private fun objectType(element: JsonObject?): String = JsonScalars.str(element, FIELD_TYPE).orEmpty()

    private fun elementCallId(element: JsonElement): String = objectCallId(element as? JsonObject)

    private fun objectCallId(element: JsonObject?): String = JsonScalars.str(element, FIELD_CALL_ID).orEmpty()
}

public data class ResponsesCodeModeInput(
    val logicalItems: List<JsonElement>,
    val replayItems: List<ResponsesCodeModeReplay>,
) {
    public val nativeSegments: List<ResponsesCodeModeReplay>
        get() = replayItems.filter { it.callbackId == null }
}

public data class ResponsesCodeModeReplay(
    val logicalOffset: Int,
    val callbackId: String?,
    val items: List<JsonElement>,
)

private data class SearchPair(
    val items: List<JsonElement>,
    val declaration: Boolean,
)

private const val NATIVE_ORDER = 0
private const val ALIAS_ORDER = 1
private const val SEARCH_PAIR_SIZE = 2
internal const val FIELD_CALL_ID = "call_id"
private const val FIELD_NAME = "name"
private const val FIELD_TOOLS = "tools"
internal const val FIELD_TYPE = "type"
private const val TOOL_DECLARATION_ID_PREFIX = "ts_decl_"
private const val TYPE_FUNCTION_CALL = "function_call"
internal const val TYPE_REASONING = "reasoning"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
