package splice.app.provider.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.app.provider.codex.CodeModeSharedMcpSearch
import splice.upstream.codemode.CodeModeManual

class CodeModeSharedMcpSearchTest {
    private val schema = Json.parseToJsonElement(
        """{"type":"object","properties":{"z":{"type":"string"},"a":{"type":"number"}}}""",
    ).jsonObject
    private val declaration = CodeModeManual.NestedTool("mcp__parity__echo", "raw description", schema)

    private fun catalog(
        name: String = "echo",
        description: String = "raw description",
        title: String = "  Raw title  ",
    ): JsonObject = buildJsonObject {
        put(
            "tools",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", name)
                        put("title", title)
                        put("description", description)
                        put("inputSchema", schema)
                    },
                )
            },
        )
    }

    @Test
    fun `the current frontend declaration binds raw metadata and preserves the distinct source search spelling`() {
        val port = CodeModeSharedMcpSearch { mapOf("parity" to catalog()) }
        val info = port.entries(listOf(declaration)).getValue(declaration.codeModeName)

        assertEquals("mcp__parity__echo", info.declarationName)
        assertEquals("mcp__parityecho", info.identity.flatName)
        assertEquals("echo", info.identity.callableName)
        assertEquals("echo", info.identity.rawToolName)
        assertEquals("parity", info.identity.serverName)
        assertEquals("  Raw title  ", info.descriptions.title)
        assertEquals("raw description", info.descriptions.description)
        assertEquals(setOf("z", "a"), info.inputPropertyNames.toSet())
    }

    @Test
    fun `a changed declaration or an unproved frontend MCP server cannot borrow the shared metadata`() {
        val port = CodeModeSharedMcpSearch { mapOf("parity" to catalog()) }
        val otherSchema = Json.parseToJsonElement("""{"type":"object"}""").jsonObject
        val candidates = listOf(
            listOf(declaration.copy(description = "different")),
            listOf(declaration.copy(inputSchema = otherSchema)),
            listOf(declaration, declaration.copy(name = "mcp__unknown__echo")),
            listOf(declaration, declaration),
        )
        candidates.forEach { tools -> assertTrue(port.entries(tools).isEmpty(), tools.toString()) }
    }

    @Test
    fun `a configured unknown source namespace keeps the matching shared identity unresolved`() {
        val port = CodeModeSharedMcpSearch { mapOf("parity" to catalog(), "mcp__parity" to null) }
        assertTrue(port.entries(listOf(declaration)).isEmpty())
    }

    @Test
    fun `different raw server tool tuples that flatten to one frontend spelling remain ambiguous`() {
        val port = CodeModeSharedMcpSearch { mapOf("a__b" to catalog("c"), "a" to catalog("b__c")) }
        val ambiguous = declaration.copy(name = "mcp__a__b__c")
        assertTrue(port.entries(listOf(ambiguous)).isEmpty())
    }

    @Test
    fun `each request retains its own catalog snapshot and a later changed catalog refuses an old declaration`() {
        var current = mapOf<String, JsonObject?>("parity" to catalog())
        val port = CodeModeSharedMcpSearch { current }
        val first = port.entries(listOf(declaration)).getValue(declaration.codeModeName)
        current = mapOf("parity" to catalog(description = "new description", title = "New title"))

        assertTrue(port.entries(listOf(declaration)).isEmpty())
        val second = port.entries(listOf(declaration.copy(description = "new description")))
            .getValue(declaration.codeModeName)
        assertEquals("  Raw title  ", first.descriptions.title)
        assertEquals("raw description", first.descriptions.description)
        assertEquals("New title", second.descriptions.title)
        assertEquals("new description", second.descriptions.description)
    }
}
