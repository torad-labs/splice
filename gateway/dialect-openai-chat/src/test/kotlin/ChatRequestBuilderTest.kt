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
import splice.core.parse.AnthropicParse
import splice.dialect.chat.ChatQuirks
import splice.dialect.chat.ChatRequestBuilder

private val CHAT = ChatQuirks(providerTag = "kimi")

private fun build(
    json: String,
    quirks: ChatQuirks = CHAT,
    compact: Boolean = false,
    model: String = "kimi-k2",
): JsonObject {
    val body = AnthropicParse.parseAnthropicBody(json).typed
    return ChatRequestBuilder(quirks)
        .build(body, upstreamModel = model, originalModel = "claude-kimi--$model", compact = compact)
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
    fun `unreadable image source leaves an honest marker instead of dropping the message - DR-94`() {
        // Vision is ON (default quirk) but the source cannot be mapped: pre-fix the image-only
        // message vanished ENTIRELY - role alternation broken, omission hidden from the model.
        val req = build(
            """{"model":"m","messages":[
                {"role":"user","content":[
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}
                ]}
            ]}""",
        )
        val user = req.messages().single()
        assertEquals("user", user["role"]?.jsonPrimitive?.content)
        val content = user["content"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            content.contains("1 image(s) omitted by kimi proxy: unreadable image source"),
            "marker missing: $content",
        )
    }

    @Test
    fun `a partially unreadable tool_result declares the dropped image - DR-94`() {
        // Pre-fix the tool-output marker fired only when ALL images dropped, so losing one of two
        // was silent - and with vision ON the old wording blamed vision the backend has.
        val req = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t9","name":"shot","input":{}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t9","content":[
                    {"type":"text","text":"took screenshot"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":""}}
                ]}]}
            ]}""",
        )
        val msgs = req.messages()
        val tool = msgs.first { it["role"]?.jsonPrimitive?.content == "tool" }
        val content = tool["content"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(
            content.contains("1 image(s) omitted by kimi proxy: unreadable image source"),
            "marker missing: $content",
        )
        // the readable sibling still rides the follow-up user message
        assertTrue(msgs.last().toString().contains("data:image/png;base64,aGk="))
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
    fun `top thinking budget emits xhigh on grok-4_6, high elsewhere`() {
        // Grok 4.6 adds the xhigh rung (xAI docs 2026-08: native on 4.6+; older models clamp it
        // to high upstream). The chat dialect serves arbitrary vendors, so the rung is gated on
        // the upstream model — an unknown vendor must never see an enum it may reject.
        val withBudget = """{"model":"m","messages":[{"role":"user","content":"hard"}],
            "thinking":{"type":"enabled","budget_tokens":64000}}"""
        assertEquals("xhigh", build(withBudget, model = "grok-4.6")["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", build(withBudget, model = "grok-4.5")["reasoning_effort"]?.jsonPrimitive?.content)
        assertEquals("high", build(withBudget, model = "deepseek-reasoner")["reasoning_effort"]?.jsonPrimitive?.content)
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
    fun `tool_choice defaults to auto when tools ride and the client sent none`() {
        // Unlike the Responses builder (gated behind quirks.emitToolChoice / lite), chat emits
        // tool_choice whenever tools ride regardless of client choice — openAiToolChoice(null) is
        // "auto", the harmless default every OpenAI-compat vendor already assumes absent the field.
        // Pinned so this centralization can't silently drift without a test noticing.
        val req = build(
            """{"model":"m","tools":[{"name":"t","input_schema":{"type":"object"}}],
                "messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("auto", req["tool_choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `no tool_choice field when there are no tools to choose from`() {
        val req = build("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        assertFalse(req.containsKey("tool_choice"))
    }

    // A compaction is built EXACTLY like a turn (2026-09-05): same system, same tools, same
    // tool_choice, same effort. The backend's prompt cache is an exact-prefix match, so the old
    // compact-only shaping (directive appended to the system message, tools stripped) cold-read
    // the whole transcript on every compaction.
    @Test
    fun `compaction is built byte-identical to a turn`() {
        val body = """{"model":"m","system":"You are helpful.",
            "thinking":{"type":"enabled","budget_tokens":40000},
            "tools":[{"name":"run","input_schema":{"type":"object"}}],
            "tool_choice":{"type":"any"},
            "messages":[
              {"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"run","input":{"c":1}}]},
              {"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"out"},
                {"type":"text","text":"Your task is to create a detailed summary of the conversation so far."}]}]}"""
        val turn = build(body)
        val compaction = build(body, compact = true)
        assertEquals(turn.toString(), compaction.toString())
        assertEquals("high", compaction["reasoning_effort"]?.jsonPrimitive?.content, "the session's effort")
        assertTrue(compaction.containsKey("tools") && compaction.containsKey("tool_choice"), "tools ride: $compaction")
        assertFalse(compaction.toString().contains("COMPACT MODE"))
    }

    @Test
    fun `a compact turn with no system prompt invents no system message`() {
        val msgs = build("""{"model":"m","messages":[{"role":"user","content":"summarize"}]}""", compact = true).messages()
        assertEquals("user", msgs.first()["role"]?.jsonPrimitive?.content)
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

// reasoning_effort overlay wall (issue #21): a real TOML value must reach ChatQuirks.emitReasoningEffort
// through the chained overlay, and null must preserve the base — the reasoning_cache precedent
// (2026-07-24 RC-5 review) is exactly this failure mode recurring.
class ReasoningEffortTomlOverlayTest {

    @Test
    fun `the overlay applies an explicit value and null keeps the base`() {
        val base = ChatQuirks(providerTag = "kimi")
        assertTrue(base.emitReasoningEffort, "default is ON")
        assertEquals(false, base.withReasoningEffortToml(false).emitReasoningEffort)
        assertEquals(true, base.withReasoningEffortToml(null).emitReasoningEffort, "null preserves the base")
        assertEquals(
            false,
            base.withReasoningEffortToml(false).withReasoningEffortToml(null).emitReasoningEffort,
            "null preserves an applied override",
        )
    }
}
