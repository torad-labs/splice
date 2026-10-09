package splice.dialect.responses.request

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.core.wire.ToolDefinition
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.PromptCachePolicy
import splice.dialect.responses.ResponsesBackendQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning
import splice.dialect.responses.reasoning.RequestEncryptedReasoning
import splice.dialect.responses.tools.ToolSearchOutput
import splice.upstream.ToolNameShortener

private const val MUSE_CAP = 64
private const val LONG_NAME = "mcp__plugin_some_long_server_name__a_long_tool_name_from_claude_code_123456789"

private fun museOptions(sessionId: String? = "session-1") = BuildOptions(
    compact = false,
    models = ModelIds(
        original = "claude-muse--muse-spark-1.3[1m]",
        upstream = "muse-spark-1.3",
    ),
    reasoning = RequestedReasoning(
        effort = "high",
        summary = "detailed",
        display = ReasoningDisplay.TEXT,
    ),
    handoff = ReasoningHandoff(
        replay = InjectPriorReasoning(false),
        includeEncrypted = RequestEncryptedReasoning(true),
        decode = { null },
    ),
    sessionId = sessionId,
)
class ResponsesToolAliasWireTest {
    @Test
    fun `session cache identity survives a changed opening message and falls back when unkeyed`() {
        val builder = ResponsesRequestBuilder(
            ResponsesQuirks(
                providerTag = "muse",
                backend = ResponsesBackendQuirks(
                    promptCache = PromptCachePolicy(
                        key = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH,
                        retention = "24h",
                    ),
                ),
            ),
        )
        val one = AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[{"role":"user","content":"one"}]}""")
        val two = AnthropicParse.parseAnthropicBody("""{"model":"m","messages":[{"role":"user","content":"two"}]}""")
        val keyedOne = builder.build(one.typed, one.raw, museOptions()).req.getValue("prompt_cache_key")
        val keyedTwo = builder.build(two.typed, two.raw, museOptions()).req.getValue("prompt_cache_key")
        assertEquals(keyedOne, keyedTwo)
        val unkeyed = builder.build(two.typed, two.raw, museOptions(null)).req.getValue("prompt_cache_key")
        assertNotEquals(keyedOne, unkeyed)
    }

    @Test
    fun `deferred tool search answers use the same bounded alias`() {
        val names = ToolNameShortener(MUSE_CAP) { }
        val output = ToolSearchOutput(names).toolSearchOutputItem(
            "call_1",
            listOf(ToolDefinition(LONG_NAME)),
            false,
            false,
            false,
        )
        val alias = output.getValue("tools").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
        assertTrue(alias.length <= MUSE_CAP)
        assertEquals(LONG_NAME, names.restore(alias))
    }

    @Test
    fun `Muse caps declarations history and selected tool without changing other providers`() {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"muse-spark-1.3","messages":[{"role":"user","content":"hello"},
                {"role":"assistant","content":[{"type":"tool_use","id":"toolu_1",
                  "name":"$LONG_NAME","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"done"}]}],
                "tools":[{"name":"$LONG_NAME","input_schema":{"type":"object","properties":{}}}],
                "tool_choice":{"type":"tool","name":"$LONG_NAME"}}""",
        )
        val opts = museOptions()
        val names = ToolNameShortener(MUSE_CAP) { }
        val quirks = ResponsesQuirks(
            providerTag = "muse",
            backend = ResponsesBackendQuirks(
                promptCache = PromptCachePolicy(
                    key = CacheKeyStrategy.SESSION_OR_FIRST_MESSAGE_HASH,
                    retention = "24h",
                ),
            ),
            tools = ResponsesToolQuirks(
                emitToolChoice = true,
            ),
        )
        val req = ResponsesRequestBuilder(quirks, names).build(parsed.typed, parsed.raw, opts).req
        val alias = req.getValue("tools").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content
        assertTrue(alias.length <= MUSE_CAP)
        assertEquals(LONG_NAME, names.restore(alias))
        assertEquals(
            alias,
            req.getValue("tool_choice").jsonObject.getValue("function").jsonObject
                .getValue("name").jsonPrimitive.content,
        )
        val replayedCall = req.getValue("input").jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(alias, replayedCall.getValue("name").jsonPrimitive.content)
        assertEquals("muse:session-1", req.getValue("prompt_cache_key").jsonPrimitive.content)
        assertEquals("24h", req.getValue("prompt_cache_retention").jsonPrimitive.content)
        assertEquals("detailed", req.getValue("reasoning").jsonObject.getValue("summary").jsonPrimitive.content)
        assertTrue(req.getValue("include").jsonArray.any { it.jsonPrimitive.content == "reasoning.encrypted_content" })
        val other = ResponsesRequestBuilder(ResponsesQuirks(providerTag = "openai"))
            .build(parsed.typed, parsed.raw, opts).req
        assertEquals(
            LONG_NAME,
            other.getValue("tools").jsonArray.first().jsonObject.getValue("name").jsonPrimitive.content,
        )
        assertNull(other["prompt_cache_retention"])
    }
}
