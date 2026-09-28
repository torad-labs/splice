package splice.provider.codex.v4388

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.provider.codex.CodeModeBridgeConfig
import splice.provider.codex.CodeModeBridgeTestSupport
import splice.provider.codex.CodexCodeModeTurnBuilder
import splice.provider.codex.CodexCodeModeValidation

/** V4-388: what rides beside exec, when the manual says tools were withheld, and which outer names are ours. */
class CodeModeExecWireTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a hosted tool stays beside exec and a withheld client tool gets the deferred note`() {
        val bridge = bridge(ScriptedRuntime(ArrayDeque()))
        val eager = bridge.injectTool(REQUEST, setOf("Read"))
        val tools = eager.getValue("input").jsonArray[0].jsonObject.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("custom", "tool_search"), tools.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("exec", tools[0].getValue("name").jsonPrimitive.content)
        assertFalse(manual(eager).contains("Some deferred nested tools"), manual(eager))
        assertEquals(REQUEST.getValue("input").jsonArray.drop(1), eager.getValue("input").jsonArray.drop(1))

        val withheld = bridge.injectTool(REQUEST, setOf("Read", "LSP"))
        assertTrue(manual(withheld).contains("Some deferred nested tools may be omitted"), manual(withheld))
    }

    @Test
    fun `two client tools with one code-mode name document only the one the cell binds, and log the other`() {
        val request = Json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer","tools":[
            {"type":"function","name":"mcp__foo_bar__x","description":"Underscored.","parameters":{"type":"object"}}]},
            {"role":"user","content":"start"}]}""",
        ).jsonObject
        val bridge = bridge(ScriptedRuntime(ArrayDeque()))
        repeat(2) { bridge.injectTool(request, setOf("mcp__foo-bar__x", "mcp__foo_bar__x")) }
        val manual = manual(bridge.injectTool(request, setOf("mcp__foo-bar__x", "mcp__foo_bar__x")))
        assertFalse(manual.contains("Underscored."), manual)
        assertTrue(manual.contains("Some deferred nested tools may be omitted"), manual)
        assertEquals(1, logLines.count { "skipping client tool 'mcp__foo_bar__x'" in it }, logLines.toString())
    }

    @Test
    fun `a deferred tool's ALL_TOOLS description carries its declaration`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"gpt-6-sol","messages":[{"role":"user","content":"start"}],"tools":[
            {"name":"LSP","description":"Language server.","input_schema":{"type":"object",
             "properties":{"line":{"type":"integer"}},"required":["line"]}}]}""",
        )
        val builder = CodexCodeModeTurnBuilder(bridge(ScriptedRuntime(ArrayDeque())), media())
        assertEquals(
            "Language server.\n\nexec tool declaration:\n```ts\n" +
                "declare const tools: { LSP(args: { line: number; }): Promise<unknown>; };\n```",
            builder.descriptions(body).getValue("LSP"),
        )
    }

    @Test
    fun `exec and the pre-V4-388 splice_exec are both this bridge's outer call`() {
        val validation = CodexCodeModeValidation(
            CodeModeBridgeConfig({ ScriptedRuntime(ArrayDeque()) }, tempDir.resolve("bridge.json")),
        )
        assertNull(validation.outer(outer(name = "exec")))
        assertNull(validation.outer(outer(name = "splice_exec")))
        assertEquals("unsupported custom tool call 'apply_patch'", validation.outer(outer(name = "apply_patch")))
    }

    private fun manual(request: JsonObject): String = request.getValue("input").jsonArray[0].jsonObject
        .getValue("tools").jsonArray[0].jsonObject.getValue("description").jsonPrimitive.content

    private companion object {
        val REQUEST: JsonObject = Json.parseToJsonElement(
            """{"input":[{"type":"additional_tools","role":"developer","tools":[
            {"type":"function","name":"Read","description":"Reads a file.","parameters":{"type":"object"}},
            {"type":"tool_search","execution":"client"}]},
            {"role":"developer","content":"base"},{"role":"user","content":"start"}]}""",
        ).jsonObject
    }
}
