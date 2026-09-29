package splice.dialect.responses.v4389

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.tools.ToolDeferralPolicy

class CodexWireGoldenTest {
    private val quirks = ResponsesQuirks(
        providerTag = "claudex",
        emitEmptyLiteInstructions = true,
        forceStrictFalse = true,
        normalizeToolSchemas = true,
        responsesLiteModelRegex = Regex("gpt-5\\.6|gpt-6", RegexOption.IGNORE_CASE),
        toolSurface = ToolDeferralPolicy(minDeferred = 1),
    )
    private val request = """{"model":"client-model","system":"Use tools as needed.",
        "messages":[{"role":"user","content":"synthetic prompt"}],
        "tools":[{"name":"splice_exec","description":"Code mode","input_schema":{"type":"object","properties":{}}},
        {"name":"mcp__synthetic_0","description":"Synthetic tool 0",
         "input_schema":{"type":"object","properties":{"value":{"type":"string"}}}}]}
    """.trimIndent()

    private fun wire(model: String): String {
        val parsed = AnthropicParse.parseAnthropicBody(request)
        return ResponsesRequestBuilder(quirks).build(
            parsed.typed,
            parsed.raw,
            BuildOptions(
                compact = false,
                originalModel = model,
                upstreamModel = model,
                configEffort = null,
                configSummary = null,
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = InjectPriorReasoning(false),
                includeEncryptedReasoning = RequestEncryptedReasoning(false),
                decodeReasoningEnvelope = { null },
            ),
        ).req.toString()
    }

    // Frozen against the builder BEFORE V4-389 changes either mode. The strings encode full DTOs,
    // including order, code-mode exec, and the client-executed search shape on lite turns.
    private val codeModeTool = """{"type":"function","name":"splice_exec","description":"Code mode",""" +
        """"strict":false,"parameters":{"type":"object","properties":{}}}"""

    // V4-390: a lite turn's function tools ride inside codex's `functions` namespace.
    private val liteFunctions = """{"type":"namespace","name":"functions","description":"","tools":[$codeModeTool]}"""
    private val mcpTool = """{"type":"function","name":"mcp__synthetic_0","description":"Synthetic tool 0",""" +
        """"strict":false,"parameters":{"type":"object","properties":{"value":{"type":"string"}}}}"""
    private val searchTool = """{"type":"tool_search","execution":"client","description":"# Tool discovery\n\n""" +
        """Searches over deferred tool metadata and exposes matching tools for the next model call.\n\n""" +
        """Some of the tools may not have been provided to you upfront, and you should use this tool """ +
        """(`tool_search`) to search for the required tools. For MCP tool discovery, always use """ +
        """`tool_search` instead of `list_mcp_resources` or `list_mcp_resource_templates`.","parameters":{ """.trimEnd() +
        """"type":"object","properties":{"query":{"type":"string","description":"Search query for deferred tools."},""" +
        """"limit":{"type":"number","description":"Maximum number of tools to return. Defaults to 8."}},""" +
        """"required":["query"],"additionalProperties":false}}"""

    @Test
    fun `Codex standard request stays byte exact`() {
        val expected = """{"model":"gpt-5.5","input":[{"role":"user","content":"synthetic prompt"}],""" +
            """"store":false,"stream":true,"prompt_cache_key":"splice-721967fb7fd9f346d20833536b34a1c1",""" +
            """"instructions":"Use tools as needed.","tools":[$codeModeTool,$mcpTool],""" +
            """"reasoning":{"effort":"high","summary":"detailed"}}"""
        assertEquals(expected, wire("gpt-5.5"))
    }

    @Test
    fun `Codex lite request keeps client tool search byte exact`() {
        val expected = """{"model":"gpt-5.6-sol","input":[{"type":"additional_tools","role":"developer",""" +
            """"tools":[$liteFunctions,$searchTool]},{"role":"developer","content":"Use tools as needed."},""" +
            """{"role":"user","content":"synthetic prompt"}],"store":false,"stream":true,""" +
            """"prompt_cache_key":"splice-721967fb7fd9f346d20833536b34a1c1","instructions":"",""" +
            """"tool_choice":"auto","parallel_tool_calls":false,""" +
            """"reasoning":{"effort":"high","summary":"detailed","context":"all_turns"}}"""
        assertEquals(expected, wire("gpt-5.6-sol"))
    }
}
