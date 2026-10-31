package splice.dialect.responses.request

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

class ResponsesToolChangeInputTest {
    /** A profile that takes tool changes inline, as Codex's does. */
    private val quirks = ResponsesQuirks(
        providerTag = "openai",
        lite = ResponsesLiteQuirks(),
        tools = ResponsesToolQuirks(acceptsInlineToolChanges = true),
    )

    private fun propertyType(tool: JsonObject, property: String): String? =
        tool.getValue("parameters").jsonObject.getValue("properties").jsonObject
            .getValue(property).jsonObject["type"]?.jsonPrimitive?.content

    @Test
    fun `a deferred reference adds at its source boundary without hiding eager tools`() {
        val request = build(
            """
            {
              "model":"m",
              "tools":[
                {"name":"weather","description":"Look up weather","input_schema":{"type":"object","properties":{}}},
                {"name":"deferred","description":"Look up transit","defer_loading":true,
                 "input_schema":{"type":"object","properties":{"line":{"type":"string"}}}}
              ],
              "messages":[
                {"role":"user","content":"Start."},
                {"role":"system","content":[
                  {"type":"text","text":"Use the tools carefully."},
                  {"type":"tool_addition","tool":{"type":"tool_reference","name":"deferred"}},
                  {"type":"text","text":"Continue."}
                ]},
                {"role":"assistant","content":"I will continue."}
              ]
            }
            """.trimIndent(),
        )

        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        val types = input.map { it["type"]?.jsonPrimitive?.content ?: it["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("user", "system", "additional_tools", "system", "assistant"), types)

        val addition = input.single { it["type"]?.jsonPrimitive?.content == "additional_tools" }
        assertEquals("developer", addition["role"]?.jsonPrimitive?.content)
        val addedTool = addition.getValue("tools").jsonArray.single().jsonObject
        assertEquals("function", addedTool["type"]?.jsonPrimitive?.content)
        assertEquals("deferred", addedTool["name"]?.jsonPrimitive?.content)
        assertEquals("string", propertyType(addedTool, "line"))

        val topLevelTools = request.getValue("tools").jsonArray
        assertEquals(listOf("weather"), topLevelTools.map { it.jsonObject["name"]?.jsonPrimitive?.content })
        assertFalse(topLevelTools.any { it.jsonObject["name"]?.jsonPrimitive?.content == "deferred" })
    }

    @Test
    fun `inline tool additions preserve their schema and history prefix remains stable`() {
        val prefix = """
            {
              "model":"m",
              "messages":[
                {"role":"user","content":"Start."},
                {"role":"system","content":[
                  {"type":"tool_addition","tool":{"type":"tool_definition","definition":{
                    "name":"inline_lookup",
                    "description":"Find a record",
                    "input_schema":{"type":"object","properties":{"key":{"type":"string"}}}
                  }}}
                ]}
              ]
            }
        """.trimIndent()
        val prior = build(prefix)
        val next = build(
            """
            {
              "model":"m",
              "messages":[
                {"role":"user","content":"Start."},
                {"role":"system","content":[
                  {"type":"tool_addition","tool":{"type":"tool_definition","definition":{
                    "name":"inline_lookup",
                    "description":"Find a record",
                    "input_schema":{"type":"object","properties":{"key":{"type":"string"}}}
                  }}}
                ]},
                {"role":"user","content":"Use the newly loaded tool."}
              ]
            }
            """.trimIndent(),
        )

        val priorInput = prior.getValue("input").jsonArray
        val nextInput = next.getValue("input").jsonArray
        assertEquals(
            priorInput,
            JsonArray(nextInput.take(priorInput.size)),
            "appending a turn must not rewrite history",
        )
        val addition = priorInput.single {
            (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "additional_tools"
        }.jsonObject
        val inline = addition.getValue("tools").jsonArray.single().jsonObject
        assertEquals("inline_lookup", inline["name"]?.jsonPrimitive?.content)
        assertEquals("string", propertyType(inline, "key"))
    }

    @Test
    fun `tool changes outside system messages stay untrusted unknown content`() {
        val request = build(
            """
            {
              "model":"m",
              "tools":[
                {"name":"weather","description":"Look up weather","input_schema":{"type":"object","properties":{}}}
              ],
              "messages":[
                {"role":"user","content":[
                  {"type":"tool_addition","tool":{"type":"tool_reference","name":"weather"}}
                ]}
              ]
            }
            """.trimIndent(),
        )

        val input = request.getValue("input").jsonArray.map { it.jsonObject }
        assertFalse(input.any { it["type"]?.jsonPrimitive?.content == "additional_tools" })
        assertTrue(input.any { it["role"]?.jsonPrimitive?.content == "user" })
        assertTrue(
            request.getValue("tools").jsonArray.any {
                it.jsonObject["name"]?.jsonPrimitive?.content == "weather"
            },
        )
    }

    private fun build(body: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(body)
        val options = BuildOptions(
            compact = false,
            models = ModelIds(original = "claude-openai--gpt-4.1", upstream = "gpt-4.1"),
            reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
        )
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req
    }
}
