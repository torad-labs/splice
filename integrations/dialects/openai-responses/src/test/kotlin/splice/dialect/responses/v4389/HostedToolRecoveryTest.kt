package splice.dialect.responses.v4389

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.request.BuildOptions
import splice.dialect.responses.request.ResponsesRequestBuilder
import splice.dialect.responses.tools.ToolDeferralPolicy
import splice.dialect.responses.tools.ToolSearchMode
import splice.dialect.responses.tools.ToolSurfaceRecovery

class HostedToolRecoveryTest {
    @Test
    fun `hosted search works when every declared tool is deferred`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"muse-spark-1.3","messages":[{"role":"user","content":"synthetic"}],
            "tools":[{"name":"mcp__synthetic","input_schema":{"type":"object"}}]}""",
        )
        val built = ResponsesRequestBuilder(
            ResponsesQuirks(
                providerTag = "muse",
                toolSurface = ToolDeferralPolicy(mode = ToolSearchMode.HOSTED, minDeferred = 1),
            ),
        ).build(
            parsed.typed,
            parsed.raw,
            BuildOptions(
                compact = false,
                originalModel = "muse-spark-1.3",
                upstreamModel = "muse-spark-1.3",
                configEffort = null,
                configSummary = null,
                showReasoning = ReasoningDisplay.TEXT,
                replayReasoning = InjectPriorReasoning(false),
                includeEncryptedReasoning = RequestEncryptedReasoning(false),
                decodeReasoningEnvelope = { null },
            ),
        )
        val tools = built.req.getValue("tools").jsonArray.map { it.jsonObject }
        assertEquals(2, tools.size)
        assertEquals("true", tools.first()["defer_loading"]?.jsonPrimitive?.content)
        assertEquals("tool_search", tools.last()["type"]?.jsonPrimitive?.content)
        assertNull(built.toolSearch)
    }

    @Test
    fun `hosted shape refusal retries this turn with all tools eager`() {
        val body = """{"model":"muse-spark-1.3","input":[{"role":"user","content":"synthetic"}],
            "store":false,"stream":true,"tools":[{"type":"function","name":"splice_exec",
            "parameters":{"type":"object"}},{"type":"function","name":"mcp__synthetic",
            "defer_loading":true,"parameters":{"type":"object"}},{"type":"tool_search"}]}"""
        val recovery = ToolSurfaceRecovery()
        assertTrue(recovery.isToolSurfaceRejection(400, "unsupported tool_search shape", ToolSearchMode.HOSTED))
        assertTrue(recovery.isToolSurfaceRejection(400, "invalid tools field", ToolSearchMode.HOSTED))
        assertEquals(false, recovery.isToolSurfaceRejection(400, "invalid tools field", ToolSearchMode.CLIENT))
        val retried = recovery.dropToolSearchTool(body, ToolSearchMode.HOSTED)
        assertNotNull(retried, "the first rejected turn must retry, not wait for the next turn")
        val tools = Json.parseToJsonElement(requireNotNull(retried)).jsonObject.getValue("tools").jsonArray
        assertEquals(
            listOf("splice_exec", "mcp__synthetic"),
            tools.map { it.jsonObject["name"]?.jsonPrimitive?.content },
        )
        assertTrue(tools.none { "defer_loading" in it.jsonObject })
        assertNull(recovery.dropToolSearchTool(retried, ToolSearchMode.HOSTED))
    }
}
