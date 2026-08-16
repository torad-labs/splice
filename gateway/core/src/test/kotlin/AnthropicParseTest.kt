// PORT-OF: the request shapes exercised by server/test/codex-proxy.test.mjs fixtures @ pre-public-port-baseline —
// string-or-blocks content both ways, system both shapes, tool_use/tool_result round-trip,
// unknown block tolerance, thinking budget, loose-field raw view.
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.wire.ImageBlock
import splice.core.wire.RedactedThinkingBlock
import splice.core.wire.TextBlock
import splice.core.wire.ToolResultBlock
import splice.core.wire.ToolUseBlock
import splice.core.wire.UnknownBlock

class AnthropicParseTest {

    @Test
    fun `string content becomes a single text block`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","messages":[{"role":"user","content":"hello"}]}""",
        )
        val blocks = body.typed.messages.single().content
        assertEquals(listOf(TextBlock("hello")), blocks)
    }

    @Test
    fun `rich fixture - tools, results, images, replay, unknown blocks, system blocks`() {
        val body = AnthropicParse.parseAnthropicBody(FIXTURE)
        val req = body.typed

        assertEquals("claude-codex--gpt-5.6-sol", req.model)
        // Byte-preserving: multi-block system concatenates with NO separator (Anthropic-native), so
        // the block's own trailing space survives and no newline is invented (review 2026-07-23).
        assertEquals("You are terse. Second part.", req.system)
        assertEquals(2, req.tools.size)
        assertEquals("run", req.tools[0].name)
        assertEquals("object", req.tools[0].inputSchema?.get("type")?.jsonPrimitive?.content)
        assertEquals(true, req.stream)
        assertEquals(32_000, req.maxTokens)
        assertEquals(10_000, req.thinking?.budgetTokens)
        assertEquals(false, req.thinking?.disabled ?: false)

        val assistant = req.messages[1].content
        val toolUse = assistant.filterIsInstance<ToolUseBlock>().single()
        assertEquals("toolu_1", toolUse.id)
        assertEquals("run", toolUse.name)
        assertEquals("ls", toolUse.input["command"]?.jsonPrimitive?.content)
        assertTrue(assistant.filterIsInstance<RedactedThinkingBlock>().single().data.isNotEmpty())

        val userTurn = req.messages[2].content
        val result = userTurn.filterIsInstance<ToolResultBlock>().single()
        assertEquals("toolu_1", result.toolUseId)
        assertEquals(TextBlock("ok"), result.content.first())
        assertTrue(result.content.any { it is ImageBlock })

        val unknown = userTurn.filterIsInstance<UnknownBlock>().single()
        assertEquals("future_widget", unknown.type)
        assertEquals("kept", unknown.raw["payload"]?.jsonPrimitive?.content)
    }

    @Test
    fun `adjacent system blocks concatenate with no invented separator`() {
        // Byte-preservation: two blocks with NO trailing whitespace must NOT gain a newline/space
        // between them (Anthropic joins system blocks back-to-back). Inventing a delimiter would
        // break cache-control prefixes and identifiers split across blocks (review 2026-07-23).
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","system":[{"type":"text","text":"prefix="},{"type":"text","text":"VALUE"}],
               "messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("prefix=VALUE", body.typed.system)
    }

    // SCH-005: kotlinx's JsonNull IS a JsonPrimitive whose .content == "null" — a null message
    // `content` must decode as no content, not as a TextBlock carrying the literal word "null".
    @Test
    fun `null message content decodes as empty, not the literal text null`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","messages":[{"role":"user","content":null}]}""",
        )
        assertEquals(emptyList<Any>(), body.typed.messages.single().content)
    }

    // SCH-004: same JsonNull mechanism — a system text block with a null "text" field must be
    // treated as absent, not as the literal word "null" injected into the system prompt.
    @Test
    fun `a null system text block is treated as absent, not the literal text null`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","system":[{"type":"text","text":null},{"type":"text","text":"kept"}],
               "messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("kept", body.typed.system)
    }

    @Test
    fun `tool_result with bare string content parses`() {
        val body = AnthropicParse.parseAnthropicBody(
            """
            {"model":"m","messages":[{"role":"user","content":[
              {"type":"tool_result","tool_use_id":"t1","content":"plain output"}
            ]}]}
            """.trimIndent(),
        )
        val result = body.typed.messages.single().content.filterIsInstance<ToolResultBlock>().single()
        assertEquals(listOf(TextBlock("plain output")), result.content)
    }

    @Test
    fun `tool_result carries the structured is_error flag`() {
        val body = AnthropicParse.parseAnthropicBody(
            """
            {"model":"m","messages":[{"role":"user","content":[
              {"type":"tool_result","tool_use_id":"t1","content":"boom","is_error":true},
              {"type":"tool_result","tool_use_id":"t2","content":"fine","is_error":false},
              {"type":"tool_result","tool_use_id":"t3","content":"unsaid"}
            ]}]}
            """.trimIndent(),
        )
        val results = body.typed.messages.single().content.filterIsInstance<ToolResultBlock>()
        // null is NOT false: a client that omits the field says nothing, and LoopGuard falls back
        // to the <tool_use_error> marker only in that case.
        assertEquals(listOf(true, false, null), results.map { it.isError })
    }

    @Test
    fun `raw view preserves loose fields the typed schema does not model`() {
        val body = AnthropicParse.parseAnthropicBody(
            """{"model":"m","messages":[],"reasoning_effort":"xhigh","metadata":{"effort":"low"}}""",
        )
        assertEquals("xhigh", body.raw["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("low", body.raw["metadata"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertNull(body.typed.thinking)
    }

    private companion object {
        val FIXTURE = """
        {
          "model": "claude-codex--gpt-5.6-sol",
          "stream": true,
          "max_tokens": 32000,
          "thinking": {"type": "enabled", "budget_tokens": 10000},
          "system": [{"type":"text","text":"You are terse. "},{"type":"text","text":"Second part."}],
          "tools": [
            {"name":"run","description":"run a command","input_schema":{"type":"object","properties":{"command":{"type":"string"}}}},
            {"name":"read","input_schema":{"type":"object"}}
          ],
          "messages": [
            {"role":"user","content":"do the thing"},
            {"role":"assistant","content":[
              {"type":"thinking","thinking":"planning..."},
              {"type":"redacted_thinking","data":"c3BsaWNlLXJlYXNvbmluZw=="},
              {"type":"text","text":"running"},
              {"type":"tool_use","id":"toolu_1","name":"run","input":{"command":"ls"}}
            ]},
            {"role":"user","content":[
              {"type":"tool_result","tool_use_id":"toolu_1","content":[
                 {"type":"text","text":"ok"},
                 {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
              ]},
              {"type":"future_widget","payload":"kept"}
            ]}
          ]
        }
        """.trimIndent()
    }
}
