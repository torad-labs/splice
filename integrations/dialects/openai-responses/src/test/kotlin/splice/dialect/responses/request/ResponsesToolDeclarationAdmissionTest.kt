package splice.dialect.responses.request

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.failure.Decline
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

/** Ordinary client functions remain callable, while unsupported execution semantics never become callable. */
class ResponsesToolDeclarationAdmissionTest {
    private val schema = """{"type":"object","properties":{"query":{"type":"string"}}}"""
    private val customTool = tool(""","type":"custom"""")

    @Test
    fun `a source custom tool remains an ordinary JSON function`() {
        assertFunctionPreserved(body(customTool))
    }

    @Test
    fun `a deferred source custom tool remains a JSON function when offered`() {
        assertFunctionPreserved(referencedBody(tool(""","type":"custom","defer_loading":true""")))
    }

    @Test
    fun `an inline source custom tool remains a JSON function`() {
        assertFunctionPreserved(body(customTool, inline = true))
    }

    @Test
    fun `any caller allowlist permitting direct execution remains a public function`() {
        listOf(
            """["direct"]""",
            """["direct","code_execution_20260120"]""",
            """["code_execution_20260521","direct","code_execution_20250825"]""",
        ).forEach { callers ->
            listOf(false, true).forEach { inline ->
                assertFunctionPreserved(body(tool(""","allowed_callers":$callers"""), inline))
            }
        }
    }

    @Test
    fun `unused withheld definitions do not advertise unsupported execution semantics`() {
        listOf(
            ""","type":"web_search_20260318"""",
            ""","allowed_callers":["code_execution_20260120"]""",
            ""","eager_input_streaming":false""",
        ).forEach { metadata ->
            val request = wireRequest(body(tool(""","defer_loading":true$metadata""")))
            assertTrue(declarations(request).none { it["name"]?.jsonPrimitive?.content == "lookup" })
        }
    }

    @Test
    fun `offering a withheld programmatic-only definition still refuses direct execution`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            referencedBody(tool(""","defer_loading":true,"allowed_callers":["code_execution_20260120"]""")),
        )
        assertEquals("TOOL_ALLOWED_CALLERS", featureName(parsed))
    }

    @Test
    fun `input examples stay model-visible without changing the function schema`() {
        repeat(16) { seed ->
            val examples = """[{"query":"example_$seed"},{"query":"snowman ☃ and \\\"quotes\\\""}]"""
            listOf(false, true).forEach { inline ->
                val definition = tool(""","description":"Look up data.","input_examples":$examples""")
                val function = assertFunctionPreserved(body(definition, inline))
                val description = function.getValue("description").jsonPrimitive.content
                assertTrue(description.startsWith("Look up data."), description)
                assertTrue(description.contains(Json.parseToJsonElement(examples).toString()), description)
                assertNull(function["input_examples"])
            }
        }
    }

    @Test
    fun `empty input examples keep the original description`() {
        val function = assertFunctionPreserved(body(tool(""","description":"Look up data.","input_examples":[]""")))
        assertEquals("Look up data.", function["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `valid source cache hints use automatic caching without native breakpoint fields`() {
        listOf(
            """{"type":"ephemeral"}""",
            """{"type":"ephemeral","ttl":"5m"}""",
            """{"type":"ephemeral","ttl":"1h"}""",
        ).forEach { hint ->
            listOf(false, true).forEach { inline ->
                val function = assertFunctionPreserved(body(tool(""","cache_control":$hint"""), inline))
                assertNull(function["cache_control"])
                assertNull(function["prompt_cache_breakpoint"])
            }
        }
    }

    @Test
    fun `eager input streaming uses the existing incremental Responses argument path`() {
        listOf(false, true).forEach { inline ->
            val function = assertFunctionPreserved(body(tool(""","eager_input_streaming":true"""), inline))
            assertNull(function["eager_input_streaming"])
        }
    }

    @Test
    fun `nonstream callers accept explicit argument buffering without changing the function`() {
        listOf(false, true).forEach { inline ->
            val function = assertFunctionPreserved(
                body(tool(""","eager_input_streaming":false"""), inline = inline, stream = false),
            )
            assertNull(function["eager_input_streaming"])
        }
    }

    @Test
    fun `native tools programmatic-only callers and explicit argument buffering still refuse`() {
        listOf(
            tool(""","allowed_callers":["code_execution_20260120"]""") to "TOOL_ALLOWED_CALLERS",
            tool(""","allowed_callers":[]""") to "TOOL_ALLOWED_CALLERS",
            tool(""","type":"web_search_20260318"""") to "TOOL_TYPE",
            tool(""","eager_input_streaming":false""") to "TOOL_EAGER_INPUT_STREAMING",
            tool(""","cache_control":{"type":"persistent"}""") to "TOOL_CACHE_CONTROL",
        ).forEach { (definition, expected) ->
            listOf(false, true).forEach { inline ->
                val parsed = AnthropicParse.parseAnthropicBody(body(definition, inline))
                assertEquals(expected, featureName(parsed), definition)
            }
        }
    }

    private fun assertFunctionPreserved(body: String): JsonObject {
        val request = wireRequest(body)
        val function = declarations(request).firstOrNull { it["name"]?.jsonPrimitive?.content == "lookup" }
        assertNotNull(function, request.toString())
        assertEquals("function", function?.get("type")?.jsonPrimitive?.content)
        assertEquals(Json.parseToJsonElement(schema), function?.get("parameters"))
        return checkNotNull(function)
    }

    private fun wireRequest(body: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(body)
        val quirks = ResponsesQuirks(providerTag = "openai", lite = ResponsesLiteQuirks())
        assertNull(ResponsesOutputSchema.refusal(parsed.typed, parsed.raw, quirks)?.cause, body)
        val options = BuildOptions(
            compact = false,
            models = ModelIds(original = "claude-openai--gpt-4.1", upstream = "gpt-4.1"),
            reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
        )
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req
    }

    private fun declarations(request: JsonObject): List<JsonObject> {
        val topLevel = request["tools"] as? JsonArray ?: JsonArray(emptyList())
        val additional = (request["input"] as JsonArray).map { it.jsonObject }
            .flatMap { (it["tools"] as? JsonArray).orEmpty() }
        return (topLevel + additional).map { it.jsonObject }
    }

    private fun tool(metadata: String): String = """{"name":"lookup","input_schema":$schema$metadata}"""

    private fun body(tool: String, inline: Boolean = false, stream: Boolean = true): String = if (!inline) {
        """{"model":"m","stream":$stream,"tools":[$tool],"messages":[{"role":"user","content":"hi"}]}"""
    } else {
        """{"model":"m","stream":$stream,"messages":[{"role":"user","content":"before"},{"role":"assistant","content":"ready"},
            {"role":"system","content":[{"type":"tool_addition","tool":{"type":"tool_definition","definition":$tool}}]},
            {"role":"user","content":"after"}]}"""
    }

    private fun referencedBody(tool: String): String =
        """{"model":"m","tools":[$tool],"messages":[{"role":"user","content":"before"},
            {"role":"assistant","content":"ready"},
            {"role":"system","content":[{"type":"tool_addition","tool":{"type":"tool_reference","name":"lookup"}}]},
            {"role":"user","content":"after"}]}"""

    private fun featureName(body: splice.core.parse.AnthropicTurnBody): String? {
        val cause = ResponsesOutputSchema.refusal(body.typed, body.raw)?.cause
        return (cause?.why as? Decline.Untranslatable)?.what?.name
    }
}
