// NEW: unit test the Anthropic Messages -> OpenAI Chat Completions request builder — wire message
// ordering (the tool-message-follows-assistant contract), assistant tool_calls shape, system-first,
// and base64 image data-url mapping. Mirrors ResponsesRequestBuilderTest conventions.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder

private val CHAT = ChatQuirks(providerTag = "kimi")

private fun build(json: String, quirks: ChatQuirks = CHAT, compact: Boolean = false): JsonObject {
    val body = parseAnthropicBody(json).typed
    return ChatRequestBuilder(quirks)
        .build(body, upstreamModel = "kimi-k2", originalModel = "claude-kimi--kimi-k2", compact = compact)
        .req
}

private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }

class ChatRequestBuilderTest {

    @Test
    fun `tool_result is emitted before sibling user text so the tool message follows the assistant`() {
        // Claude Code packs [tool_result, text] into one user message; the `tool` message must
        // immediately follow the assistant tool_calls, not sit after an interposed user message.
        val msgs = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t7","name":"run","input":{"a":1}}]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t7","content":"tool output"},
                    {"type":"text","text":"now do the next thing"}
                ]}
            ]}""",
        ).messages()
        assertEquals(listOf("assistant", "tool", "user"), msgs.map { it["role"]?.jsonPrimitive?.content })
        val toolMsg = msgs[1]
        assertEquals("t7", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("tool output", toolMsg["content"]?.jsonPrimitive?.content)
        // the tool message answers the id the assistant tool_calls carried
        val callId = msgs[0]["tool_calls"]!!.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.content
        assertEquals("t7", callId)
        assertEquals("now do the next thing", msgs[2]["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tool_result only produces a tool message and no empty user message`() {
        val roles = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"run","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"ok"}]}
            ]}""",
        ).messages().map { it["role"]?.jsonPrimitive?.content }
        assertEquals(listOf("assistant", "tool"), roles)
        assertFalse(roles.contains("user"))
    }

    @Test
    fun `system message is emitted first`() {
        val first = build(
            """{"model":"m","system":"you are helpful","messages":[{"role":"user","content":"hi"}]}""",
        ).messages().first()
        assertEquals("system", first["role"]?.jsonPrimitive?.content)
        assertEquals("you are helpful", first["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant text and tool_calls share one message`() {
        val assistant = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[
                    {"type":"text","text":"let me run that"},
                    {"type":"tool_use","id":"t3","name":"run","input":{"x":2}}
                ]}
            ]}""",
        ).messages().single()
        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        assertEquals("let me run that", assistant["content"]?.jsonPrimitive?.content)
        val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("t3", call["id"]?.jsonPrimitive?.content)
        assertEquals("function", call["type"]?.jsonPrimitive?.content)
        val fn = call["function"]!!.jsonObject
        assertEquals("run", fn["name"]?.jsonPrimitive?.content)
        assertEquals("""{"x":2}""", fn["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun `base64 image maps to a data-url image_url part alongside text`() {
        val user = build(
            """{"model":"m","messages":[{"role":"user","content":[
                {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}},
                {"type":"text","text":"what is this"}
            ]}]}""",
        ).messages().single { it["role"]?.jsonPrimitive?.content == "user" }
        val parts = user["content"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            parts.any {
                it["type"]?.jsonPrimitive?.content == "image_url" &&
                    it["image_url"]!!.jsonObject["url"]?.jsonPrimitive?.content == "data:image/png;base64,aGk="
            },
        )
        assertTrue(
            parts.any {
                it["type"]?.jsonPrimitive?.content == "text" &&
                    it["text"]?.jsonPrimitive?.content == "what is this"
            },
        )
    }

    @Test
    fun `tool_result images ride a follow-up user message with a reference marker`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":[
                    {"type":"text","text":"took screenshot"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
                ]}]}
            ]}""",
        )
        val msgs = req.messages()
        val tool = msgs.first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("took screenshot", tool["content"]?.jsonPrimitive?.content)
        val follower = msgs.last()
        assertEquals("user", follower["role"]?.jsonPrimitive?.content)
        assertTrue(follower.toString().contains("images from tool_result t1"))
        assertTrue(follower.toString().contains("data:image/png;base64,aGk="))
    }

    @Test
    fun `no-vision quirk leaves honest omission markers instead of silent drops`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"user","content":[
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
                ]}
            ]}""",
            quirks = ChatQuirks(providerTag = "kimi", supportsVision = false),
        )
        val user = req.messages().single()
        val content = user["content"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(content.contains("1 image(s) omitted by kimi proxy"), "marker missing: $content")
    }

    @Test
    fun `tool_result images without vision are declared inside the tool output`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t2","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t2","content":[
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
                ]}]}
            ]}""",
            quirks = ChatQuirks(providerTag = "kimi", supportsVision = false),
        )
        val tool = req.messages().first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertTrue(tool["content"]?.jsonPrimitive?.content.orEmpty().contains("image(s) omitted"))
        // and no dangling follow-up user message for images that were dropped
        assertFalse(req.messages().last().toString().contains("images from tool_result"))
    }

    @Test
    fun `document blocks leave an omission marker`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"user","content":[
                    {"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"aGk="}},
                    {"type":"text","text":"see attached"}
                ]}
            ]}""",
        )
        val user = req.messages().single()
        assertTrue(user["content"]?.jsonPrimitive?.content.orEmpty().contains("document omitted by kimi proxy"))
    }

    @Test
    fun `reasoning_effort is gated by the quirk`() {
        val on = build("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        assertTrue(on.containsKey("reasoning_effort"))
        val off = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            quirks = ChatQuirks(providerTag = "kimi", emitReasoningEffort = false),
        )
        assertFalse(off.containsKey("reasoning_effort"))
        assertFalse(off.containsKey("reasoning"))
    }

    @Test
    fun `tool_choice maps Anthropic types onto the chat wire`() {
        val none = build(
            """{"model":"m","tools":[{"name":"t","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"none"},"messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("none", none["tool_choice"]?.jsonPrimitive?.content)
        val any = build(
            """{"model":"m","tools":[{"name":"t","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"any"},"messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("required", any["tool_choice"]?.jsonPrimitive?.content)
        val specific = build(
            """{"model":"m","tools":[{"name":"run","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"tool","name":"run"},"messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("function", specific["tool_choice"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals(
            "run",
            specific["tool_choice"]?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `compact inherits session effort and strips tools plus tool_choice`() {
        // AGENTS cache law: compact mismatch on effort cold-starts the prompt cache. Compact also
        // strips tools — and with them tool_choice, else a tool_choice with no tools 400s strict
        // vendors. Supplying real tools + tool_choice makes the stripping assertions bite.
        val compact = build(
            """{"model":"m","thinking":{"type":"enabled","budget_tokens":40000},
                "tools":[{"name":"run","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"any"},
                "messages":[{"role":"user","content":"summarize"}]}""",
            compact = true,
        )
        assertEquals("high", compact["reasoning_effort"]?.jsonPrimitive?.content)
        assertFalse(compact.containsKey("tools"))
        assertFalse(compact.containsKey("tool_choice"))
    }

    @Test
    fun `parallel tool_results with images keep tool messages contiguous`() {
        val msgs = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[
                    {"type":"tool_use","id":"t1","name":"shot","input":{}},
                    {"type":"tool_use","id":"t2","name":"run","input":{}}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t1","content":[
                        {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
                    ]},
                    {"type":"tool_result","tool_use_id":"t2","content":"ok"}
                ]}
            ]}""",
        ).messages()
        val roles = msgs.map { it["role"]?.jsonPrimitive?.content }
        // assistant, tool, tool, then user(images) — never tool, user, tool
        assertEquals(listOf("assistant", "tool", "tool", "user"), roles)
    }
}
