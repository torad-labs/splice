package splice.provider.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.buildResponsesTestRequest

/**
 * a code_mode_only turn carries the surface codex-rs sends
 * (core/src/tools/spec_plan.rs is_hidden_by_code_mode_only + code_mode/execute_spec.rs): ONE top-level
 * `exec` freeform tool with the lark grammar, every client tool nested in its manual as a TypeScript
 * declaration, and no client function tool beside it. Before V4-388 splice sent every client tool
 * top-level plus a 478-char `splice_exec` with a text format, and the model called exec on 0-10% of turns.
 */
class CodeModeExecSurfaceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `exec is the only top-level tool and its manual declares every client tool`() {
        val prepared = prepare()
        val entries = prepared.getValue("input").jsonArray[0].jsonObject.getValue("tools").jsonArray
        assertEquals(1, entries.size, "top-level tools: ${entries.map { it.jsonObject["name"] }}")
        // V4-390: the one top-level entry is codex's functions namespace, and exec is its only member.
        val functions = entries.single().jsonObject
        assertEquals("namespace", functions.getValue("type").jsonPrimitive.content)
        assertEquals("functions", functions.getValue("name").jsonPrimitive.content)
        val tools = functions.getValue("tools").jsonArray
        assertEquals(1, tools.size, "functions members: ${tools.map { it.jsonObject["name"] }}")
        val exec = tools.single().jsonObject
        assertEquals("custom", exec.getValue("type").jsonPrimitive.content)
        assertEquals("exec", exec.getValue("name").jsonPrimitive.content)
        assertEquals(
            Json.parseToJsonElement(
                """{"type":"grammar","syntax":"lark","definition":${Json.encodeToString(GRAMMAR)}}""",
            ),
            exec.getValue("format"),
        )
        val manual = exec.getValue("description").jsonPrimitive.content
        assertTrue(manual.startsWith("Run JavaScript code to orchestrate/compose tool calls\n"), manual)
        assertTrue(manual.contains("### `Read`\nReads a file from the local filesystem."), manual)
        assertTrue(
            manual.contains(
                "declare const tools: { Read(args: { file_path: string; limit?: number; }): Promise<unknown>; };",
            ),
            manual,
        )
        assertTrue(manual.contains("### `mcp__ast_grep__find_code` (`mcp__ast-grep__find_code`)"), manual)
        assertTrue(manual.contains("mcp__ast_grep__find_code(args: {\n  // The pattern to find\n"), manual)
    }

    private fun prepare(): JsonObject {
        val raw = buildJsonObject {
            put("model", "gpt-6-sol")
            put("system", "Caller instructions")
            put("tools", Json.parseToJsonElement(TOOLS))
            put("messages", Json.parseToJsonElement("""[{"role":"user","content":"start"}]"""))
        }
        val body = AnthropicParse.parseAnthropicBody(raw.toString())
        val request = buildResponsesTestRequest(
            ResponsesQuirks(
                providerTag = "test",
                lite = ResponsesLiteQuirks(
                    responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
                    emitEmptyLiteInstructions = false,
                ),
            ),
            body,
            model = "gpt-6-sol",
        )
        val built = built("gpt-6-sol", lite = true).copy(requestBody = request)
        val builder = CodexCodeModeTurnBuilder(
            bridge(ScriptedRuntime(ArrayDeque())),
            media(),
            codeModeOnly = backendCodeModeOnly,
        )
        return builder.prepare(body, "session", built).requestBody
    }

    private companion object {
        const val TOOLS = """[
            {"name":"Read","description":"Reads a file from the local filesystem.","input_schema":{"type":"object",
             "properties":{"file_path":{"type":"string"},"limit":{"type":"integer"}},"required":["file_path"]}},
            {"name":"mcp__ast-grep__find_code","description":"Find code with an ast-grep pattern.",
             "input_schema":{"type":"object","properties":{"pattern":{"type":"string",
             "description":"The pattern to find"}},"required":["pattern"]}}
        ]"""

        /** codex-rs core/src/tools/code_mode/execute_spec.rs CODE_MODE_FREEFORM_GRAMMAR. */
        const val GRAMMAR = """
start: pragma_source | plain_source
pragma_source: PRAGMA_LINE NEWLINE SOURCE
plain_source: SOURCE

PRAGMA_LINE: /[ \t]*\/\/ @exec:[^\r\n]*/
NEWLINE: /\r?\n/
SOURCE: /[\s\S]+/
"""
    }
}
