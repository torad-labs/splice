// NEW: shape-400 recovery for the deferred tool surface — split from ToolSurface.kt (2026-07-24
// hardening: that file was at detekt's TooManyFunctions ceiling). Owns the RC-4-style amend path:
// detect a backend rejection of the tool_search SHAPE, strip every invented item, and hand back a
// request that is a genuinely clean status-quo-shaped body for the ONE-SHOT UpstreamClient retry
// (amendedOnce, UpstreamClient.kt) to land on.
// Invariant (review 2026-07-24, HIGH): the first cut of this recovery stripped only the tool_search
// TOOL entry from the additional_tools item, leaving orphaned tool_search_call / tool_search_output
// items in `input` — a rejection of THOSE invented items (the shape splice itself authors, not
// codex's) could not be recovered, since the one-shot retry re-POSTed a body still carrying them.
// [dropToolSearchTool] now strips BOTH: the tool, and every search-call/output item plus their
// now-dangling preceding reasoning items (a reasoning item with nothing after it is itself a 400 —
// the same rule ResponsesFoldController.continuation guards).
package splice.dialect.responses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import splice.core.util.strOrEmpty

/** Deliberately broad (status-shape, not exact wording) — a narrower literal match is exactly
 *  what the 2026-07-24 review flagged as letting a backend wording drift skip the recovery. */
internal fun isToolSurfaceRejection(status: Int, responseText: String): Boolean {
    if (status < REJECTION_STATUS_MIN || status > REJECTION_STATUS_MAX) return false
    val lower = responseText.lowercase()
    return lower.contains(TYPE_TOOL_SEARCH) || lower.contains(FIELD_DEFER_LOADING)
}

/** RC-4-style shape recovery: decode the closed DTO, strip every invented tool-surface item, then
 *  re-encode. Null = nothing to strip (the rejection was not ours to fix; the caller's plain retry
 *  plan applies instead). */
internal fun dropToolSearchTool(bodyJson: String): String? {
    val parsed = Json.parseToJsonElement(bodyJson).jsonObject
    val base = responsesRequestJson.decodeFromJsonElement(ResponsesRequest.serializer(), parsed)
    if (!hasToolSearchTool(base.input) && !hasToolSearchItems(base.input)) return null
    val toolStripped = buildJsonArray { base.input.forEach { add(stripToolSearchFromItem(it)) } }
    val next = base.copy(input = stripToolSearchItems(toolStripped))
    return responsesRequestJson.encodeToJsonElement(ResponsesRequest.serializer(), next).toString()
}

private fun hasToolSearchTool(input: JsonArray): Boolean = input.any { item ->
    ((item as? JsonObject)?.get(FIELD_TOOLS) as? JsonArray)?.any(::isToolSearchTool) == true
}

private fun hasToolSearchItems(input: JsonArray): Boolean = input.any { isSearchCallOrOutput(itemType(it)) }

/** [item] unchanged, except an item carrying a `tools` array has its tool_search entry filtered. */
private fun stripToolSearchFromItem(item: JsonElement): JsonElement {
    val obj = item as? JsonObject ?: return item
    val tools = obj[FIELD_TOOLS] as? JsonArray ?: return item
    return buildJsonObject {
        obj.forEach { (k, v) -> if (k != FIELD_TOOLS) put(k, v) }
        put(FIELD_TOOLS, buildJsonArray { tools.forEach { if (!isToolSearchTool(it)) add(it) } })
    }
}

private fun isToolSearchTool(t: JsonElement): Boolean = itemType(t) == TYPE_TOOL_SEARCH

/** Drops every tool_search_call/tool_search_output item, plus the contiguous run of "reasoning"
 *  items immediately preceding a dropped call — see the file header for why those would otherwise
 *  dangle. Two passes (mark, then extend the mark backward over reasoning) kept as separate
 *  functions so neither trips CyclomaticComplexMethod on its own. */
private fun stripToolSearchItems(input: JsonArray): JsonArray {
    val drop = markSearchCallOutputs(input)
    markDanglingReasoning(input, drop)
    return buildJsonArray { input.forEachIndexed { i, item -> if (!drop[i]) add(item) } }
}

private fun markSearchCallOutputs(input: JsonArray): BooleanArray {
    val drop = BooleanArray(input.size)
    for (i in input.indices) {
        if (isSearchCallOrOutput(itemType(input[i]))) drop[i] = true
    }
    return drop
}

private fun markDanglingReasoning(input: JsonArray, drop: BooleanArray) {
    for (i in input.indices) {
        if (!drop[i] || itemType(input[i]) != TYPE_TOOL_SEARCH_CALL) continue
        var j = i - 1
        while (isPrecedingReasoning(input, drop, j)) {
            drop[j] = true
            j--
        }
    }
}

// Split into a guard + a plain check (not one 3-operand &&) — ComplexCondition fails at 3 operands.
private fun isPrecedingReasoning(input: JsonArray, drop: BooleanArray, j: Int): Boolean {
    if (j < 0 || drop[j]) return false
    return itemType(input[j]) == TYPE_REASONING
}

private fun isSearchCallOrOutput(type: String): Boolean =
    type == TYPE_TOOL_SEARCH_CALL || type == TYPE_TOOL_SEARCH_OUTPUT

private fun itemType(t: JsonElement): String = strOrEmpty((t as? JsonObject)?.get(FIELD_TYPE))

private const val REJECTION_STATUS_MIN = 400
private const val REJECTION_STATUS_MAX = 422

private const val FIELD_TYPE = "type"
private const val FIELD_TOOLS = "tools"
private const val FIELD_DEFER_LOADING = "defer_loading"
private const val TYPE_TOOL_SEARCH = "tool_search"
private const val TYPE_TOOL_SEARCH_CALL = "tool_search_call"
private const val TYPE_TOOL_SEARCH_OUTPUT = "tool_search_output"
private const val TYPE_REASONING = "reasoning"
