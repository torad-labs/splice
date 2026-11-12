// PORT-OF: codex-rs/core/src/tools/handlers/tool_search.rs @ rust-v0.164.0.
package splice.upstream.codemode

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import splice.core.util.JsonScalars
import splice.upstream.codemode.search.CodeModeBm25
import splice.upstream.codemode.search.CodeModeSearchEntry
import java.math.BigInteger

/** One immutable current catalog; replacements rebuild both corpus statistics and rendered declarations. */
public class CodeModeRankedSearch(
    private val entries: List<CodeModeSearchEntry>,
    allowedNames: Set<String>,
    toolDescriptionFirst: Boolean = false,
) {
    private val index = CodeModeBm25(entries.map { it.searchText })
    private val reachable = entries.flatMap { it.tools }.filter { it.codeModeName in allowedNames }
        .distinctBy { CodeModeManual.identifier(it.codeModeName) }
    private val definitions = CodeModeManual.prepare(reachable, toolDescriptionFirst)
        .associateBy { it.codeModeName }

    public fun search(arguments: JsonObject): JsonObject {
        val query = (arguments["query"] as? JsonPrimitive)?.takeIf { it.isString }?.let(JsonScalars::str)
            ?: throw IllegalArgumentException("failed to parse function arguments: query must be a string")
        val trimmed = CodeModeText.trim(query)
        require(trimmed.isNotEmpty()) { "query must not be empty" }
        val limit = limit(arguments)
        val tools = ArrayList<CodeModeManual.NestedTool>()
        for (match in index.matches(trimmed)) {
            tools += entries[match.index].tools.mapNotNull(::resolve).take(limit - tools.size)
            if (tools.size == limit) break
        }
        return buildJsonObject { put("tools", kotlinx.serialization.json.JsonArray(tools.map(::item))) }
    }

    private fun resolve(candidate: CodeModeManual.NestedTool): CodeModeManual.NestedTool? =
        definitions[candidate.codeModeName]?.takeIf {
            it.name == candidate.name && namespace(it) == namespace(candidate)
        }

    private fun item(tool: CodeModeManual.NestedTool): JsonObject = buildJsonObject {
        put("name", CodeModeManual.identifier(tool.codeModeName))
        put("description", tool.description)
    }

    private fun namespace(tool: CodeModeManual.NestedTool): String =
        tool.metadata.namespace?.takeIf { it.isNotEmpty() } ?: "functions"

    private fun limit(arguments: JsonObject): Int {
        val supplied = arguments["limit"]?.takeUnless { it is JsonNull } ?: return CodeModeToolSearch.DEFAULT_LIMIT
        val number = (supplied as? JsonPrimitive)?.takeUnless { it.isString }
            ?.let { JsonScalars.str(it) }?.toBigIntegerOrNull()
        require(number != null && number.signum() >= 0 && number <= MAX_SEARCH_LIMIT) {
            "failed to parse function arguments: limit must be a non-negative integer"
        }
        require(number.signum() != 0) { "limit must be greater than zero" }
        return number.min(BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }
}

private val MAX_SEARCH_LIMIT = CodeModeUnsigned.maximum
