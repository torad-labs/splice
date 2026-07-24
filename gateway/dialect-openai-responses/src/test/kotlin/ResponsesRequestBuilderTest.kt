// PORT-OF: buildRequest pins from server/test/codex-proxy.test.mjs @ pre-public-port-baseline — effort
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

    // codex-rs responses-lite parity for the gpt-5.6 family (shape read from codex-rs source and
    // accepted by the live backend 2026-07-19): instructions+tools move INTO input, parallel tool
    // calls forced off, reasoning context spans the session.
    @Test
    fun `gpt-5-6 lite shape - instructions and tools ride as input items`() {
        val tooled = """{"model":"m","system":"harness prompt",
            "tools":[{"name":"Task","input_schema":{"type":"object"}}],
            "messages":[{"role":"user","content":"x"}]}"""
        val req = build(tooled, options = opts(model = "gpt-5.6-sol"))
        assertNull(req["instructions"])
        assertNull(req["tools"])
        // codex-rs parity (client.rs:896): tools ride as additional_tools, so the backend needs an
        // explicit tool_choice:"auto" to enable function-calling — omitting it left the model
        // improvising tool calls (stuck/looping turns). Emitted even though codex leaves emitToolChoice off.
        assertEquals("auto", req["tool_choice"]?.jsonPrimitive?.content)
        assertEquals("false", req["parallel_tool_calls"]?.jsonPrimitive?.content)
        assertEquals("all_turns", req["reasoning"]?.jsonObject?.get("context")?.jsonPrimitive?.content)
        val input = req["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("additional_tools", input[0]["type"]?.jsonPrimitive?.content)
        assertEquals("developer", input[0]["role"]?.jsonPrimitive?.content)
        assertEquals("Task", input[0]["tools"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("developer", input[1]["role"]?.jsonPrimitive?.content)
        assertEquals("harness prompt", input[1]["content"]?.jsonPrimitive?.content)
        assertEquals("x", input[2]["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gpt-5-6 lite without tools - developer instructions only, no additional_tools item`() {
        val req = build(
            """{"model":"m","system":"harness prompt","messages":[{"role":"user","content":"x"}]}""",
            options = opts(model = "gpt-5.6-luna"),
        )
        assertNull(req["instructions"])
        // the backend REQUIRES an explicit false whenever the lite header rides, tools or not
        assertEquals("false", req["parallel_tool_calls"]?.jsonPrimitive?.content)
        val input = req["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals("developer", input[0]["role"]?.jsonPrimitive?.content)
        assertEquals("harness prompt", input[0]["content"]?.jsonPrimitive?.content)
        assertFalse(input.any { it["type"]?.jsonPrimitive?.content == "additional_tools" })
    }

    @Test
    fun `non-lite models keep the normal shape - instructions and tools top-level, no context`() {
        val tooled = """{"model":"m","system":"harness prompt",
            "tools":[{"name":"Task","input_schema":{"type":"object"}}],
            "messages":[{"role":"user","content":"x"}]}"""
        val req = build(tooled, options = opts(model = "gpt-5.5"))
        assertEquals("harness prompt", req["instructions"]?.jsonPrimitive?.content)
        assertEquals("Task", req["tools"]!!.jsonArray[0].jsonObject["name"]?.jsonPrimitive?.content)
        // non-lite codex keeps its proven shape: top-level tools auto-enable calling, so tool_choice
        // stays omitted (the lite tool_choice fix must not perturb this path).
        assertNull(req["tool_choice"])
        assertNull(req["parallel_tool_calls"])
        assertNull(req["reasoning"]?.jsonObject?.get("context"))
        assertFalse(
            req["input"]!!.jsonArray.any {
                it.jsonObject["type"]?.jsonPrimitive?.content == "additional_tools"
            },
        )
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

    // CACHE LAW (2026-07-20): compaction MUST run on the session's own model AND effort, or the
    // warm prompt-cache prefix is invalidated and the whole ~160k transcript re-reads cold (the
    // "compaction ate my subscription" bug). Codex has no compact-model override and no
    // compactEffortPin, so both inherit the session. Pins the removal of the compactModel footgun.
    @Test
    fun `compact inherits the session model and effort - codex cache law`() {
        val body = """{"model":"m","system":"base","messages":[{"role":"user","content":"go"}]}"""
        val req = build(body, options = opts(compact = true, effort = "high", model = "gpt-5.6-sol"))
        // model is the session's own upstream model — never swapped for a compaction run
        assertEquals("gpt-5.6-sol", req["model"]?.jsonPrimitive?.content)
        // effort is inherited from the session (config "high"), not pinned to low/other on codex
        assertEquals("high", req["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
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

// RC-3 walls (reasoning-cache 2026-07-24): cache hits inject the turn's reasoning ONCE,
// immediately before its FIRST function_call; misses are byte-identical to today; the rs_ id
// appears at most once per request even when the legacy client-replay path carries it too.
private fun cacheOpts(
    replay: Boolean = false,
    lookup: (String) -> List<String>? = { null },
) = BuildOptions(
    compact = false,
    originalModel = "claude-codex--gpt-5.6-sol",
    upstreamModel = "gpt-5.6-sol",
    configEffort = null,
    configSummary = null,
    showReasoning = ReasoningDisplay.from("text"),
    replayReasoning = InjectPriorReasoning(replay),
    includeEncryptedReasoning = RequestEncryptedReasoning(true),
    sessionId = null,
    decodeReasoningEnvelope = { data ->
        buildJsonObject {
            put("type", JsonPrimitive("reasoning"))
            put("id", JsonPrimitive("rs_$data"))
            put("encrypted_content", JsonPrimitive(data))
        }
    },
    reasoningLookup = lookup,
)

private const val TOOL_TURN_BODY = """{"model":"m","messages":[
    {"role":"user","content":"do the thing"},
    {"role":"assistant","content":[
        {"type":"tool_use","id":"call_abc","name":"run","input":{"x":1}}]},
    {"role":"user","content":[
        {"type":"tool_result","tool_use_id":"call_abc","content":[{"type":"text","text":"ok"}]}]}
]}"""

class ReasoningInjectionTest {

    private fun items(req: JsonObject) = req["input"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `a cache hit injects the turn's reasoning immediately before its function_call`() {
        val req = build(
            TOOL_TURN_BODY,
            options = cacheOpts(lookup = { id -> if (id == "call_abc") listOf("env1") else null }),
        )
        val input = items(req)
        val fcIdx = input.indexOfFirst { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertTrue(fcIdx > 0, "function_call present")
        assertEquals("reasoning", input[fcIdx - 1]["type"]?.jsonPrimitive?.content)
        assertEquals("rs_env1", input[fcIdx - 1]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `one turn's entry injects once even with two tool_use blocks`() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":"go"},
            {"role":"assistant","content":[
                {"type":"tool_use","id":"call_a","name":"run","input":{}},
                {"type":"tool_use","id":"call_b","name":"run","input":{}}]},
            {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call_a","content":[{"type":"text","text":"1"}]},
                {"type":"tool_result","tool_use_id":"call_b","content":[{"type":"text","text":"2"}]}]}
        ]}"""
        val req = build(body, options = cacheOpts(lookup = { listOf("env1") }))
        val input = items(req)
        val reasonings = input.filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(1, reasonings.size, "inject-once per turn")
        val firstFc = input.indexOfFirst { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("reasoning", input[firstFc - 1]["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a cache miss is byte-identical to an unwired build`() {
        val hit = build(TOOL_TURN_BODY, options = cacheOpts(lookup = { null }))
        val unwired = build(TOOL_TURN_BODY, options = cacheOpts())
        assertEquals(unwired.toString(), hit.toString())
    }

    @Test
    fun `cache and legacy client replay never duplicate an rs_ id`() {
        val body = """{"model":"m","messages":[
            {"role":"user","content":"go"},
            {"role":"assistant","content":[
                {"type":"redacted_thinking","data":"env1"},
                {"type":"tool_use","id":"call_abc","name":"run","input":{}}]},
            {"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call_abc","content":[{"type":"text","text":"ok"}]}]}
        ]}"""
        val req = build(body, options = cacheOpts(replay = true, lookup = { listOf("env1") }))
        val reasonings = items(req).filter { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals(1, reasonings.size, "rs_env1 must appear exactly once across both paths")
    }
}
