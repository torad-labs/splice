// NEW: separates native Responses replay items from code mode's logical client history.
package splice.dialect.responses

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import splice.core.util.JsonScalars

/** Separates Responses-only replay items from the client-visible conversation used by code mode. */
public class ResponsesCodeModeProjection {
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
        val next = input.drop(pairIndex + SEARCH_PAIR_SIZE).dropWhile { elementType(it) == TYPE_REASONING }
            .firstOrNull() as? JsonObject
        if (objectType(next) != TYPE_FUNCTION_CALL) return false
        val functionName = JsonScalars.str(next, FIELD_NAME).orEmpty()
        val tools = output[FIELD_TOOLS] as? JsonArray ?: return false
        return tools.any { tool ->
            JsonScalars.str(tool as? JsonObject, FIELD_NAME) == functionName
        }
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
private const val FIELD_CALL_ID = "call_id"
private const val FIELD_NAME = "name"
private const val FIELD_TOOLS = "tools"
private const val FIELD_TYPE = "type"
private const val TYPE_FUNCTION_CALL = "function_call"
private const val TYPE_REASONING = "reasoning"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
