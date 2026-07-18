// NEW: unit test the near-identity Anthropic -> Kimi passthrough request builder — raw-field
// preservation, deep cache_control strip, block allowlist, thinking->adaptive+output_config effort
// ladder, compact tool/effort handling, and sampling-strip quirk. Mirrors ChatRequestBuilderTest.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.dialect.passthrough.BuiltPassthroughRequest
import splice.dialect.passthrough.PassthroughQuirks
import splice.dialect.passthrough.PassthroughRequestBuilder

private val PASS = PassthroughQuirks(providerTag = "kimi")

private fun buildFull(
    json: String,
    quirks: PassthroughQuirks = PASS,
    compact: Boolean = false,
): BuiltPassthroughRequest {
    val body = parseAnthropicBody(json)
    return PassthroughRequestBuilder(quirks)
        .build(body, upstreamModel = "k3", originalModel = "claude-kimi--k3[1m]", compact = compact)
}

private fun build(json: String, quirks: PassthroughQuirks = PASS, compact: Boolean = false): JsonObject =
    buildFull(json, quirks, compact).req

private fun JsonObject.messages() = this["messages"]!!.jsonArray.map { it.jsonObject }
private fun JsonObject.blocks(msg: Int) = messages()[msg]["content"]!!.jsonArray.map { it.jsonObject }
private fun JsonObject.blockTypes(msg: Int) = blocks(msg).map { it["type"]?.jsonPrimitive?.content }

class PassthroughRequestBuilderTest {

    @Test
    fun `model is replaced with upstream id and stream forced true`() {
        val req = build("""{"model":"claude-sonnet","messages":[{"role":"user","content":"hi"}],"stream":false}""")
        assertEquals("k3", req["model"]?.jsonPrimitive?.content)
        assertEquals(true, req["stream"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `unknown top-level fields ride through verbatim`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "max_tokens":256,"stop_sequences":["X"],"metadata":{"user_id":"u1"},"vendor_flag":"keep"}""",
        )
        assertEquals("keep", req["vendor_flag"]?.jsonPrimitive?.content)
        assertEquals(256, req["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("X", req["stop_sequences"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("u1", req["metadata"]!!.jsonObject["user_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cache_control is deep-stripped from system, message blocks, and tools`() {
        val req = build(
            """{"model":"m",
                "system":[{"type":"text","text":"sys","cache_control":{"type":"ephemeral"}}],
                "messages":[{"role":"user","content":[
                    {"type":"text","text":"hi","cache_control":{"type":"ephemeral"}}
                ]}],
                "tools":[{"name":"run","input_schema":{"type":"object"},"cache_control":{"type":"ephemeral"}}]}""",
        )
        assertFalse(req.toString().contains("cache_control"))
    }

    @Test
    fun `disallowed block types are dropped at message and tool_result levels`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"user","content":[
                    {"type":"text","text":"keep"},
                    {"type":"document","source":{"type":"base64","data":"x"}},
                    {"type":"tool_reference","name":"z"},
                    {"type":"redacted_thinking","data":"r"},
                    {"type":"mystery_block","foo":1}
                ]},
                {"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"t1","content":[
                        {"type":"text","text":"ok"},
                        {"type":"document","source":{"type":"base64","data":"x"}},
                        {"type":"weird","foo":1}
                    ]}
                ]}
            ]}""",
        )
        assertEquals(listOf("text"), req.blockTypes(0))
        val toolResult = req.blocks(1).single()
        val inner = toolResult["content"]!!.jsonArray.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertEquals(listOf("text"), inner)
    }

    @Test
    fun `thinking block with signature passes verbatim`() {
        val block = build(
            """{"model":"m","messages":[{"role":"assistant","content":[
                {"type":"thinking","thinking":"deep thought","signature":"sig-abc"}
            ]}]}""",
        ).blocks(0).single()
        assertEquals("thinking", block["type"]?.jsonPrimitive?.content)
        assertEquals("deep thought", block["thinking"]?.jsonPrimitive?.content)
        assertEquals("sig-abc", block["signature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `whitespace-only thinking without signature is dropped`() {
        val types = build(
            """{"model":"m","messages":[{"role":"assistant","content":[
                {"type":"thinking","thinking":"   "},
                {"type":"text","text":"answer"}
            ]}]}""",
        ).blockTypes(0)
        assertEquals(listOf("text"), types)
    }

    @Test
    fun `whitespace thinking WITH a signature survives`() {
        val types = build(
            """{"model":"m","messages":[{"role":"assistant","content":[
                {"type":"thinking","thinking":"   ","signature":"s"}
            ]}]}""",
        ).blockTypes(0)
        assertEquals(listOf("thinking"), types)
    }

    // --- thinking -> adaptive + output_config effort ladder --------------------------------------

    private fun thinkingReq(budget: String?): JsonObject {
        val budgetField = budget?.let { ""","budget_tokens":$it""" } ?: ""
        return build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "thinking":{"type":"enabled"$budgetField}}""",
        )
    }

    private fun JsonObject.effort() = this["output_config"]!!.jsonObject["effort"]?.jsonPrimitive?.content
    private fun JsonObject.thinkingShape() = this["thinking"]!!.jsonObject

    @Test
    fun `enabled thinking maps to adaptive summarized with output_config`() {
        val req = thinkingReq("30000")
        assertEquals("adaptive", req.thinkingShape()["type"]?.jsonPrimitive?.content)
        assertEquals("summarized", req.thinkingShape()["display"]?.jsonPrimitive?.content)
    }

    @Test
    fun `effort ladder rungs map budget to low high max without medium`() {
        assertEquals("max", thinkingReq("24576").effort()) // >= 24576
        assertEquals("max", thinkingReq("40000").effort())
        assertEquals("high", thinkingReq("8192").effort()) // >= 8192
        assertEquals("high", thinkingReq("20000").effort())
        assertEquals("low", thinkingReq("1").effort()) // > 0
        assertEquals("low", thinkingReq("8191").effort())
        assertEquals("max", thinkingReq(null).effort()) // absent -> native default
    }

    @Test
    fun `disabled thinking omits both thinking and output_config`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"thinking":{"type":"disabled"}}""",
        )
        assertNull(req["thinking"])
        assertNull(req["output_config"])
    }

    @Test
    fun `absent thinking omits both keys`() {
        val req = build("""{"model":"m","messages":[{"role":"user","content":"hi"}]}""")
        assertNull(req["thinking"])
        assertNull(req["output_config"])
    }

    // --- tools + compaction ----------------------------------------------------------------------

    @Test
    fun `tools carry sanitized input_schema, drop strict, default description`() {
        val tool = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "tools":[{"name":"run","strict":true,"input_schema":{"properties":{"a":{"type":"string"}}}}]}""",
        )["tools"]!!.jsonArray.single().jsonObject
        assertNull(tool["strict"])
        assertEquals("", tool["description"]?.jsonPrimitive?.content)
        // MFJS filled the missing object type from `properties`.
        assertEquals("object", tool["input_schema"]!!.jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `compact strips tools and tool_choice and INHERITS the session effort`() {
        val built = buildFull(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "tools":[{"name":"run","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"auto"},"thinking":{"type":"enabled","budget_tokens":40000}}""",
            compact = true,
        )
        assertNull(built.req["tools"])
        assertNull(built.req["tool_choice"])
        // v27 doctrine canary: compact inherits the session's own effort — the 40k budget rides
        // the same "max" rung a non-compact turn gets. Pinning must be an explicit quirk opt-in;
        // if this assertion ever sees a pinned value again, the default regressed.
        assertEquals("max", built.req.effort())
        assertEquals("max", built.meta.effort)
    }

    @Test
    fun `an explicitly configured compact pin still applies to compact turns only`() {
        val pinned = PASS.copy(compactEffort = "low")
        val compacted = buildFull(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "thinking":{"type":"enabled","budget_tokens":40000}}""",
            quirks = pinned,
            compact = true,
        )
        assertEquals("low", compacted.req.effort())
        val session = buildFull(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],
                "thinking":{"type":"enabled","budget_tokens":40000}}""",
            quirks = pinned,
        )
        assertEquals("max", session.req.effort())
    }

    // --- sampling strip quirk --------------------------------------------------------------------

    @Test
    fun `sampling params pass through when the strip quirk is off`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"temperature":0.4,"top_p":0.9,"top_k":40}""",
        )
        assertEquals("0.4", req["temperature"]?.jsonPrimitive?.content)
        assertEquals("0.9", req["top_p"]?.jsonPrimitive?.content)
        assertEquals("40", req["top_k"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sampling params are removed when the strip quirk is on`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":"hi"}],"temperature":0.4,"top_p":0.9,"top_k":40}""",
            quirks = PASS.copy(stripSamplingParams = true),
        )
        assertNull(req["temperature"])
        assertNull(req["top_p"])
        assertNull(req["top_k"])
    }

    // --- meta ------------------------------------------------------------------------------------

    @Test
    fun `meta records mirror-off reasoning, models, and effort`() {
        val meta = buildFull(
            """{"model":"claude-sonnet","messages":[{"role":"user","content":"hi"}],
                "max_tokens":512,"thinking":{"type":"enabled","budget_tokens":9000}}""",
        ).meta
        // mirror must be a no-op — passthrough emits real thinking blocks
        assertFalse(meta.showReasoning == ReasoningDisplay.TEXT)
        assertEquals("k3", meta.upstreamModel)
        assertEquals("claude-kimi--k3[1m]", meta.originalModel)
        assertEquals("high", meta.effort)
        assertEquals(512, meta.clientMaxTokens?.toInt())
        assertEquals(9000, meta.budgetTokens?.toInt())
        assertNull(meta.summary)
    }
}
