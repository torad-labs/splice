// Walls for ToolSearchIndex's deterministic field-weighted ranking (ToolSearchIndex.kt): the
// corpus is name / name-with-spaces / description / property names+descriptions, weighted so a
// name hit always outranks a description/property hit; ties break by name for reproducibility.
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ToolSearchIndex

private fun tool(name: String, description: String? = null, properties: Map<String, String> = emptyMap()) =
    ToolDefinition(
        name = name,
        description = description,
        inputSchema = if (properties.isEmpty()) {
            null
        } else {
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    properties.forEach { (propName, propDesc) ->
                        putJsonObject(propName) {
                            put("type", "string")
                            put("description", propDesc)
                        }
                    }
                }
            }
        },
    )

class ToolSearchIndexTest {

    @Test
    fun `exact-name query ranks the named tool first`() {
        val target = tool("mcp__exa__web_search_exa", description = "Search the web")
        val decoys = listOf(tool("mcp__exa__web_fetch_exa", description = "Fetch a URL"), tool("mcp__ast_grep__find"))
        val index = ToolSearchIndex(listOf(target) + decoys)
        val results = index.search("mcp__exa__web_search_exa", limit = 5)
        assertEquals(target.name, results.first().name)
    }

    @Test
    fun `underscore-split matching finds the tool via a spaced query`() {
        val target = tool("mcp__exa__web_search_exa", description = "Full text web search")
        val other = tool("mcp__ast_grep__find_code", description = "Structural code search")
        val index = ToolSearchIndex(listOf(target, other))
        val results = index.search("web search exa", limit = 5)
        assertEquals(target.name, results.first().name)
    }

    @Test
    fun `property-name and property-description hits score below name hits`() {
        // "billing" hits toolA's NAME (weight 3) and toolB's property description ONLY (weight 1).
        val toolA = tool("mcp__billing__nothing_relevant", description = "unrelated")
        val toolB = tool(
            "mcp__other__configure",
            description = "configure things",
            properties = mapOf("mode" to "the billing mode to apply"),
        )
        val index = ToolSearchIndex(listOf(toolB, toolA))
        val results = index.search("billing", limit = 5)
        assertEquals(toolA.name, results.first().name, "a name hit outranks a property-description hit")
        assertTrue(results.contains(toolB))
    }

    @Test
    fun `zero-match query yields an empty list, never a throw`() {
        val index = ToolSearchIndex(listOf(tool("mcp__exa__web_search_exa"), tool("mcp__ast_grep__find")))
        assertTrue(index.search("no such tool anywhere", limit = 5).isEmpty())
    }

    @Test
    fun `determinism - equal scores tie-break by name, repeated runs are identical`() {
        val toolA = tool("mcp__alpha__widget", description = "manage widget")
        val toolB = tool("mcp__beta__widget", description = "manage widget")
        val index = ToolSearchIndex(listOf(toolB, toolA))
        val first = index.search("widget", limit = 5)
        val second = index.search("widget", limit = 5)
        assertEquals(first, second)
        assertEquals(listOf(toolA.name, toolB.name), first.map { it.name }, "equal score ties break by name")
    }

    @Test
    fun `limit is respected and all() returns the full set in input order`() {
        val tools = List(20) { tool("mcp__exa__tool_$it", description = "search widget") }
        val index = ToolSearchIndex(tools)
        assertEquals(3, index.search("widget", limit = 3).size)
        assertEquals(tools, index.all())
    }
}
