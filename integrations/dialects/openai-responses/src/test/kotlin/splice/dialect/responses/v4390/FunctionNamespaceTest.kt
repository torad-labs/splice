// NEW: V4-390 part 2 — a lite turn's tools ride grouped into the `functions` namespace, the list
// codex-rs create_tools_json_for_responses_lite builds (tools/src/tool_spec.rs:95-142).
package splice.dialect.responses.v4390

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesFunctionNamespace
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder

private val LITE_QUIRKS = ResponsesQuirks(providerTag = "claudex", responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6"))

private const val TOOLS_BODY = """{"model":"claude-codex--gpt-6-sol","stream":true,"max_tokens":1024,
    "system":"You are Splice.",
    "messages":[{"role":"user","content":"Fix the bug."}],
    "tools":[
      {"name":"Read","description":"Reads a file.","input_schema":{"type":"object"}},
      {"name":"Bash","description":"Runs a command.","input_schema":{"type":"object"}}]}"""

class FunctionNamespaceTest {
    private val namespace = ResponsesFunctionNamespace()

    @Test
    fun `function and custom tools share one functions namespace where the first stood, hosted tools keep their places`() {
        val grouped = namespace.group(
            tools(
                """[{"type":"web_search"},{"type":"function","name":"Read"},{"type":"custom","name":"exec"},
                {"type":"tool_search","execution":"client"},{"type":"function","name":"Bash"}]""",
            ),
        )
        assertEquals(
            tools(
                """[{"type":"web_search"},
                {"type":"namespace","name":"functions","description":"","tools":[
                  {"type":"function","name":"Read"},{"type":"custom","name":"exec"},{"type":"function","name":"Bash"}]},
                {"type":"tool_search","execution":"client"}]""",
            ),
            grouped,
        )
        // codex's serde order, byte for byte: the tag, then name, description, tools.
        assertEquals(
            """{"type":"namespace","name":"functions","description":"","tools":[""",
            grouped[1].toString().substringBefore("{\"type\":\"function\""),
        )
    }

    @Test
    fun `an incoming functions namespace is merged and keeps its description, and grouping twice changes nothing`() {
        val grouped = namespace.group(
            tools(
                """[{"type":"namespace","name":"functions","description":"Client tools.","tools":[
                {"type":"function","name":"Read"}]},{"type":"function","name":"Bash"}]""",
            ),
        )
        assertEquals(
            tools(
                """[{"type":"namespace","name":"functions","description":"Client tools.","tools":[
                {"type":"function","name":"Read"},{"type":"function","name":"Bash"}]}]""",
            ),
            grouped,
        )
        assertEquals(grouped, namespace.group(grouped))
        assertEquals(
            listOf("Read", "Bash"),
            namespace.members(grouped).map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
    }

    @Test
    fun `a list with nothing to group is returned as it came, and another namespace is left alone`() {
        val hosted =
            tools("""[{"type":"web_search"},{"type":"namespace","name":"slack","description":"x","tools":[]}]""")
        assertEquals(hosted, namespace.group(hosted))
        assertEquals(hosted.toList(), namespace.members(hosted))
    }

    @Test
    fun `a lite turn's additional_tools item carries the grouped list, and a non-lite turn's tools are unchanged`() {
        val lite = request("gpt-6-sol")
        val additional = lite.getValue("input").jsonArray[0].jsonObject
        assertEquals("additional_tools", additional.getValue("type").jsonPrimitive.content)
        val entries = additional.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("namespace"), entries.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("functions", entries[0].getValue("name").jsonPrimitive.content)
        assertEquals(
            listOf("Read", "Bash"),
            entries[0].getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertNull(lite["tools"])

        val plain = request("gpt-5.5").getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(listOf("function", "function"), plain.map { it.getValue("type").jsonPrimitive.content })
    }

    private fun tools(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    private fun request(upstreamModel: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(TOOLS_BODY)
        val opts = BuildOptions(
            compact = false,
            originalModel = "claude-codex--$upstreamModel",
            upstreamModel = upstreamModel,
            configEffort = "high",
            configSummary = "detailed",
            showReasoning = ReasoningDisplay.TEXT,
            replayReasoning = InjectPriorReasoning(false),
            decodeReasoningEnvelope = { null },
        )
        return ResponsesRequestBuilder(LITE_QUIRKS).build(parsed.typed, parsed.raw, opts).req
    }
}
