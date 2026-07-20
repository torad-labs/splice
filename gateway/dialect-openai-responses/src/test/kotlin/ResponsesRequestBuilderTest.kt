// PORT-OF: buildRequest pins from server/test/codex-proxy.test.mjs @ 4ca99f7 — effort
// precedence (v27), visibility floor semantics, spark summary quirk, compact stripping +
// instruction forcing + same-model/effort inheritance, cache-key stability, tool mapping,
// replay gating, grok ladder clamps, purity/determinism.
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.CacheKeyStrategy
import splice.dialect.responses.EffortLadder
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesRequestBuilder
import splice.dialect.responses.stablePromptCacheKey

private val CODEX = ResponsesQuirks(providerTag = "claudex")
private val GROK = ResponsesQuirks(
    providerTag = "claude-grok",
    cacheKeyStrategy = CacheKeyStrategy.SESSION_ID,
    effortLadder = EffortLadder.GROK,
    supportsSummary = true,
    summaryRejectModelRegex = null,
    compactEffortPin = "low",
    emitToolChoice = true,
    emitStrict = true,
)

private fun opts(
    compact: Boolean = false,
    effort: String? = null,
    summary: String? = null,
    show: String = "text",
    replay: Boolean = false,
    includeEncrypted: Boolean? = null,
    model: String = "gpt-5.6-sol",
    sessionId: String? = null,
) = BuildOptions(
    compact = compact,
    originalModel = "claude-codex--$model",
    upstreamModel = model,
    configEffort = effort,
    configSummary = summary,
    showReasoning = ReasoningDisplay.from(show),
    replayReasoning = InjectPriorReasoning(replay),
    // Default: include when reasoning is shown (independent of input-replay).
    includeEncryptedReasoning = RequestEncryptedReasoning(includeEncrypted ?: (show != "off" && !compact)),
    sessionId = sessionId,
    decodeReasoningEnvelope = { data ->
        buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("decoded", JsonPrimitive(data))
        }
    },
)

private fun build(json: String, quirks: ResponsesQuirks = CODEX, options: BuildOptions = opts()): JsonObject {
    val parsed = parseAnthropicBody(json)
    return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req
}

class ResponsesRequestBuilderTest {

    @Test
    fun `effort precedence - body beats budget beats config, ultracode maps to max`() {
        val budgetBody = """{"model":"m","thinking":{"type":"enabled","budget_tokens":32000},
            "messages":[{"role":"user","content":"x"}]}"""
        // budget 32k -> xhigh (beats config low)
        var req = build(budgetBody, options = opts(effort = "low"))
        assertEquals("xhigh", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        // explicit body field beats the budget
        req = build(
            """{"model":"m","effort":"ultracode","thinking":{"type":"enabled","budget_tokens":2000},
                "messages":[{"role":"user","content":"x"}]}""",
        )
        assertEquals("max", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        // nothing anywhere -> high
        req = build("""{"model":"m","messages":[{"role":"user","content":"x"}]}""")
        assertEquals("high", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `visibility floor lifts none-minimal to low but never a deliberate pick`() {
        var req = build(
            """{"model":"m","effort":"minimal","messages":[{"role":"user","content":"x"}]}""",
            options = opts(show = "text"),
        )
        assertEquals("low", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertEquals("detailed", req["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        req = build(
            """{"model":"m","effort":"medium","messages":[{"role":"user","content":"x"}]}""",
            options = opts(show = "text", summary = "concise"),
        )
        assertEquals("medium", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        // configSummary is operator-controlled — concise stays concise when TOML/env says so.
        assertEquals("concise", req["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
    }

    @Test
    fun `request-level weak summary is floored to detailed when reasoning is visible`() {
        // The MODEL/Claude Code asking for concise must not defeat operator-visible reasoning
        // (v27 fold); an operator-set concise still wins (pinned in the visibility-floor test).
        val req = build(
            """{"model":"m","reasoning":{"summary":"concise"},"messages":[{"role":"user","content":"x"}]}""",
            options = opts(show = "text"),
        )
        assertEquals("detailed", req["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
    }

    // stream_options.reasoning_summary_delivery (2026-07-19): rides ONLY with a codex-style
    // summaryDelivery quirk AND an actual summary request — grok/default quirks omit the field.
    @Test
    fun `summary delivery rides with the quirk and an actual summary, never otherwise`() {
        val body = """{"model":"m","thinking":{"type":"enabled","budget_tokens":32000},
            "messages":[{"role":"user","content":"x"}]}"""
        val withDelivery = CODEX.copy(summaryDelivery = "sequential_cutoff")
        var req = build(body, quirks = withDelivery)
        assertEquals(
            "sequential_cutoff",
            req["stream_options"]?.jsonObject?.get("reasoning_summary_delivery")?.jsonPrimitive?.content,
        )
        // no quirk -> omitted (grok/openai-platform)
        req = build(body, quirks = GROK)
        assertNull(req["stream_options"])
        // quirk set but no summary requested (disabled thinking) -> omitted, codex-rs parity
        val disabled = """{"model":"m","thinking":{"type":"disabled"},
            "messages":[{"role":"user","content":"x"}]}"""
        req = build(disabled, quirks = withDelivery)
        assertNull(req["stream_options"])
    }

    @Test
    fun `disabled thinking emits no reasoning block on codex`() {
        val req = build(
            """{"model":"m","thinking":{"type":"disabled"},"messages":[{"role":"user","content":"x"}]}""",
        )
        assertNull(req["reasoning"])
    }

    @Test
    fun `mini clamps effort max to xhigh, other models keep max`() {
        val maxBody = """{"model":"m","effort":"max","messages":[{"role":"user","content":"x"}]}"""
        var req = build(maxBody, options = opts(model = "gpt-5.4-mini"))
        assertEquals("xhigh", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        req = build(maxBody, options = opts(model = "gpt-5.6-sol"))
        assertEquals("max", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        // the 64k budget tier maps to max — the real-world trigger — and clamps on mini too
        req = build(
            """{"model":"m","thinking":{"type":"enabled","budget_tokens":64000},
                "messages":[{"role":"user","content":"x"}]}""",
            options = opts(model = "gpt-5.4-mini"),
        )
        assertEquals("xhigh", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `spark rejects the summary field`() {
        val req = build(
            """{"model":"m","messages":[{"role":"user","content":"x"}]}""",
            options = opts(model = "gpt-5.3-codex-spark"),
        )
        assertEquals("high", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertNull(req["reasoning"]?.jsonObject?.get("summary"))
    }

    @Test
    fun `compact strips tools, forces text-only instructions, folds tool results`() {
        val req = build(
            """{"model":"m","system":"base system",
                "tools":[{"name":"run","input_schema":{"type":"object"}}],
                "messages":[{"role":"user","content":[
                  {"type":"tool_result","tool_use_id":"t1","content":"result body"},
                  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}},
                  {"type":"text","text":"please compact"}
                ]}]}""",
            options = opts(compact = true),
        )
        assertNull(req["tools"])
        val instructions = req["instructions"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(instructions.startsWith("base system"))
        assertTrue(instructions.contains("COMPACT MODE (critical)"))
        assertTrue(instructions.contains("No tools. No function calls."))
        val inputs = req["input"]!!.jsonArray.map { it.jsonObject }
        assertTrue(inputs.any { it["content"]?.jsonPrimitive?.content == "[tool_result t1] result body" })
        // images dropped on compact
        assertFalse(inputs.any { it.toString().contains("input_image") })
    }

    @Test
    fun `tool_use and tool_result map to function_call items - images ride follow-up`() {
        val req = build(
            """{"model":"m","messages":[
                {"role":"assistant","content":[{"type":"tool_use","id":"t9","name":"run","input":{"c":1}}]},
                {"role":"user","content":[{"type":"tool_result","tool_use_id":"t9","content":[
                    {"type":"text","text":"out"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"aGk="}}
                ]}]}
            ]}""",
        )
        val inputs = req["input"]!!.jsonArray.map { it.jsonObject }
        val call = inputs.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("t9", call["call_id"]?.jsonPrimitive?.content)
        assertEquals("""{"c":1}""", call["arguments"]?.jsonPrimitive?.content)
        val output = inputs.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("out", output["output"]?.jsonPrimitive?.content)
        val follower = inputs.last()
        assertTrue(follower.toString().contains("images from tool_result t9"))
        assertTrue(follower.toString().contains("data:image/png;base64,aGk="))
    }

    @Test
    fun `include and input-replay are independent knobs`() {
        val body = """{"model":"m","messages":[{"role":"assistant","content":[
            {"type":"redacted_thinking","data":"ZW52ZWxvcGU="}]},
            {"role":"user","content":"next"}]}"""
        // include ON, replay OFF (the deep-reasoning default): fetch handle, do not inject prior.
        val includeOnly = build(body, options = opts(replay = false, includeEncrypted = true))
        assertTrue(includeOnly["include"].toString().contains("reasoning.encrypted_content"))
        assertFalse(includeOnly["input"]!!.jsonArray.any { it.jsonObject["decoded"] != null })
        // NB: no stream_options on Responses — the ChatGPT backend 400s "Unknown parameter"
        // on stream_options.include_usage (verified live 2026-07-18); usage rides response.completed.
        assertNull(includeOnly["stream_options"])
        // both ON: inject prior redacted_thinking into input AND request new encrypted handle.
        val both = build(body, options = opts(replay = true, includeEncrypted = true))
        assertTrue(both["include"].toString().contains("reasoning.encrypted_content"))
        assertTrue(both["input"]!!.jsonArray.any { it.jsonObject["decoded"] != null })
        // both OFF: neither include nor inject.
        val off = build(body, options = opts(replay = false, includeEncrypted = false))
        assertNull(off["include"])
        assertFalse(off["input"]!!.jsonArray.any { it.jsonObject["decoded"] != null })
    }

    @Test
    fun `cache key - stable sha prefix on codex, session id on grok, null without seed`() {
        val body = """{"model":"m","messages":[{"role":"user","content":"first message"}]}"""
        val a = build(body)["prompt_cache_key"]?.jsonPrimitive?.content
        val b = build(body)["prompt_cache_key"]?.jsonPrimitive?.content
        assertEquals(a, b)
        assertTrue(a!!.startsWith("splice-") && a.length == "splice-".length + 32)
        val parsed = parseAnthropicBody("""{"model":"m","messages":[]}""")
        assertNull(stablePromptCacheKey(parsed.typed))
        val grokReq = build(body, quirks = GROK, options = opts(sessionId = "sess-1"))
        assertEquals("claude-grok:sess-1", grokReq["prompt_cache_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `grok ladder clamps xhigh to high, emits tool_choice, floors disabled to low`() {
        val req = build(
            """{"model":"grok-4.5","effort":"xhigh","tools":[{"name":"t","input_schema":{"type":"object"}}],
                "tool_choice":{"type":"any"},"messages":[{"role":"user","content":"x"}]}""",
            quirks = GROK,
            options = opts(model = "grok-4.5"),
        )
        assertEquals("high", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        // Full reasoning visibility: detailed summary is requested so the stream fills the
        // thinking channel (xAI's public form of "full" reasoning text).
        assertEquals("detailed", req["reasoning"]?.jsonObject?.get("summary")?.jsonPrimitive?.content)
        assertEquals("required", req["tool_choice"]?.jsonPrimitive?.content)
        assertEquals(true, req["parallel_tool_calls"]?.jsonPrimitive?.content?.toBoolean())
        // disabled thinking does NOT disable grok reasoning — the default chain applies
        // (Node grok source: budget skipped, config||high; the low-floor is only for
        // out-of-ladder values)
        val disabled = build(
            """{"model":"grok-4.5","thinking":{"type":"disabled"},"messages":[{"role":"user","content":"x"}]}""",
            quirks = GROK,
            options = opts(model = "grok-4.5"),
        )
        assertEquals("high", disabled["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
    }

    @Test
    fun `purity - identical inputs build identical requests, store false, stream true`() {
        val body = """{"model":"m","messages":[{"role":"user","content":"same"}]}"""
        assertEquals(build(body).toString(), build(body).toString())
        val req = build(body)
        assertEquals("false", req["store"]?.jsonPrimitive?.content)
        assertEquals("true", req["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `grok compaction pins reasoning effort to low regardless of session budget`() {
        val body = """{"model":"grok-4.5","thinking":{"type":"enabled","budget_tokens":50000},
            "messages":[{"role":"user","content":"summarize"}]}"""
        val req = build(body, quirks = GROK, options = opts(compact = true, model = "grok-4.5"))
        assertEquals("low", req["reasoning"]!!.jsonObject["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a malformed reasoning field (bare string) does not crash the builder`() {
        // Node's optional chaining degrades to defaults; the Kotlin port must not throw on
        // `?.jsonObject` (safe cast instead). This would otherwise be an uncaught 500 in HeadServer.
        val body = """{"model":"gpt-5.6-sol","reasoning":"high","metadata":"x","output_config":"y",
            "messages":[{"role":"user","content":"hi"}]}"""
        val req = build(body) // must not throw
        assertTrue(req.containsKey("reasoning")) // falls back to config/default effort
    }

    @Test
    fun `compact instructions have no blank line between system prompt and COMPACT MODE`() {
        val body = """{"model":"gpt-5.6-sol","system":"base system",
            "messages":[{"role":"user","content":"go"}]}"""
        val req = build(body, options = opts(compact = true))
        val instructions = req["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.startsWith("base system\nCOMPACT MODE")) // \n not \n\n (Node .filter(Boolean))
    }

    @Test
    fun `image with empty media_type falls back to image-png like Node`() {
        val body = """{"model":"gpt-5.6-sol","messages":[{"role":"user","content":[
            {"type":"image","source":{"type":"base64","media_type":"","data":"aGk="}}]}]}"""
        val req = build(body)
        assertTrue(req.toString().contains("data:image/png;base64,aGk="))
    }
}
