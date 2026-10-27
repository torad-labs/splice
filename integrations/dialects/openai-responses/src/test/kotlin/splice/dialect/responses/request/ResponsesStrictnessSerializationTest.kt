package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.wire.ToolDefinition
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.tools.ToolWireObjects

class ResponsesStrictnessSerializationTest {
    private val toolWire = ToolWireObjects()
    private val quirks = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())

    @Test
    fun `ordinary and deferred declarations explicitly preserve every strictness choice`() {
        val choices = listOf<Boolean?>(null, false, true)
        val expected = listOf("false", "false", "true")

        val ordinary = choices.mapIndexed { index, strict ->
            toolWire.functionToolObject(
                ToolDefinition(name = "ordinary_$index", strict = strict),
                emitStrict = true,
                forceStrictFalse = false,
                normalizeSchemas = false,
            )
        }
        val deferred = choices.mapIndexed { index, strict ->
            toolWire.deferredToolObject(
                ToolDefinition(name = "deferred_$index", strict = strict),
                emitStrict = true,
                forceStrictFalse = false,
                normalizeSchemas = false,
            )
        }

        assertEquals(expected, ordinary.map { it["strict"]?.jsonPrimitive?.content })
        assertEquals(expected, deferred.map { it["strict"]?.jsonPrimitive?.content })
    }

    @Test
    fun `additional function declarations explicitly preserve every strictness choice`() {
        val body =
            """
            {
              "model":"m",
              "messages":[
                {"role":"system","content":[
                  {"type":"tool_addition","tool":{"type":"tool_definition","definition":{
                    "name":"omitted","input_schema":{"type":"object"}}}},
                  {"type":"tool_addition","tool":{"type":"tool_definition","definition":{
                    "name":"false","strict":false,"input_schema":{"type":"object"}}}},
                  {"type":"tool_addition","tool":{"type":"tool_definition","definition":{
                    "name":"true","strict":true,"input_schema":{"type":"object"}}}}
                ]},
                {"role":"user","content":"Use a tool."}
              ]
            }
            """.trimIndent()
        // A profile that takes tool changes inline declares each added tool where the conversation added it; any other
        // profile lists it. Both write every strictness choice explicitly.
        val inline = quirks.copy(tools = quirks.tools.copy(acceptsInlineToolChanges = true))
        val declared = build(body, inline).getValue("input").jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "additional_tools" }
            .map { it.getValue("tools").jsonArray.single().jsonObject }
        val listed = build(body).getValue("tools").jsonArray.map { it.jsonObject }

        for (tools in listOf(declared, listed)) {
            assertEquals(listOf("false", "false", "true"), tools.map { it["strict"]?.jsonPrimitive?.content })
        }
    }

    @Test
    fun `the Codex forced false policy remains authoritative`() {
        val request = toolWire.functionToolObject(
            ToolDefinition(name = "strict_source", strict = true),
            emitStrict = true,
            forceStrictFalse = true,
            normalizeSchemas = false,
        )

        assertEquals("false", request["strict"]?.jsonPrimitive?.content)
    }

    @Test
    fun `strict true is declined when a profile cannot carry it`() {
        val unsupported = quirks.copy(tools = ResponsesToolQuirks(emitStrict = false))
        val declarations = listOf(
            """
            {"model":"m","tools":[
              {"name":"ordinary","strict":true,"input_schema":{"type":"object"}},
              {"name":"deferred","strict":true,"defer_loading":true,"input_schema":{"type":"object"}}
            ],"messages":[{"role":"user","content":"Use a tool."}]}
            """.trimIndent(),
            """
            {"model":"m","messages":[
              {"role":"system","content":[{"type":"tool_addition","tool":{
                "type":"tool_definition","definition":{
                  "name":"additional","strict":true,"input_schema":{"type":"object"}
                }
              }}]},
              {"role":"user","content":"Use a tool."}
            ]}
            """.trimIndent(),
        )

        declarations.forEach { body ->
            val parsed = AnthropicParse.parseAnthropicBody(body)
            val cause = ResponsesOutputSchema.refusal(parsed.typed, parsed.raw, unsupported)?.cause
            val feature = (cause?.why as? Decline.Untranslatable)?.what?.name
            assertEquals("TOOL_STRICTNESS", feature, body)
        }
    }

    @Test
    fun `unoffered strict definitions do not require a profile to enforce their schema`() {
        val unsupported = quirks.copy(tools = ResponsesToolQuirks(emitStrict = false))
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","tools":[{"name":"withheld","strict":true,"defer_loading":true,
                "input_schema":{"type":"object"}}],"messages":[{"role":"user","content":"hi"}]}""",
        )

        assertEquals(null, ResponsesOutputSchema.refusal(parsed.typed, parsed.raw, unsupported))
    }

    @Test
    fun `offering a strict deferred tool requires profile strictness support`() {
        val unsupported = quirks.copy(tools = ResponsesToolQuirks(emitStrict = false))
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","tools":[{"name":"withheld","strict":true,"defer_loading":true,
                "input_schema":{"type":"object"}}],"messages":[
                {"role":"user","content":"before"},{"role":"assistant","content":"ready"},
                {"role":"system","content":[{"type":"tool_addition","tool":{
                  "type":"tool_reference","name":"withheld"}}]},{"role":"user","content":"after"}]}""",
        )

        val cause = ResponsesOutputSchema.refusal(parsed.typed, parsed.raw, unsupported)?.cause
        assertEquals("TOOL_STRICTNESS", (cause?.why as? Decline.Untranslatable)?.what?.name)
    }

    private fun build(body: String, profile: ResponsesQuirks = quirks): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(body)
        return ResponsesRequestBuilder(profile).build(parsed.typed, parsed.raw, options()).req
    }

    private fun options(): BuildOptions = BuildOptions(
        compact = false,
        models = ModelIds(original = "claude-openai--gpt-4.1", upstream = "gpt-4.1"),
        reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
        handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
    )
}
