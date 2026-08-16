// Walls for the deferred tool surface partition (ToolSurface.kt). Pins the NEVER-BELOW-STATUS-QUO
// gate order (off/latch/non-lite/compact/floor/degenerate all fall back to all-eager), transcript
// INDEPENDENCE (the partition is a pure function of (body.tools, policy) ONLY — cache-prefix
// stability, 2026-07-25; replaces the removed R2 always-eager-promotion, whose declaration-replay
// successor is pinned in ResponsesRequestBuilderTest.kt), the eager/defer overrides, and the
// shape-400 recovery helpers.
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.turn.ReasoningDisplay
import splice.core.wire.AnthropicMessage
import splice.core.wire.AnthropicRequest
import splice.core.wire.ToolChoice
import splice.core.wire.ToolDefinition
import splice.core.wire.ToolUseBlock
import splice.dialect.responses.BuildOptions
import splice.dialect.responses.InjectPriorReasoning
import splice.dialect.responses.RequestEncryptedReasoning
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ToolDeferralPolicy
import splice.dialect.responses.ToolPartitioner
import splice.dialect.responses.ToolSurfaceRecovery

private val recovery = ToolSurfaceRecovery()

private fun opts(
    compact: Boolean = false,
    model: String = "gpt-5.6-sol",
    toolSurfaceOpen: Boolean = true,
): BuildOptions = BuildOptions(
    compact = compact,
    originalModel = "claude-codex--$model",
    upstreamModel = model,
    configEffort = null,
    configSummary = null,
    showReasoning = ReasoningDisplay.from("text"),
    replayReasoning = InjectPriorReasoning(false),
    includeEncryptedReasoning = RequestEncryptedReasoning(true),
    sessionId = null,
    decodeReasoningEnvelope = { null },
    toolSurfaceOpen = toolSurfaceOpen,
)

private fun mcpTools(n: Int, prefix: String = "mcp__exa__tool_") = List(n) { ToolDefinition(name = "$prefix$it") }
private fun builtins(n: Int) = List(n) { ToolDefinition(name = "Builtin$it") }

private fun requestOf(
    tools: List<ToolDefinition>,
    messages: List<AnthropicMessage> = emptyList(),
    toolChoice: ToolChoice? = null,
): AnthropicRequest = AnthropicRequest(model = "m", messages = messages, tools = tools, toolChoice = toolChoice)

private val POLICY = ToolDeferralPolicy()
private val QUIRKS_OFF = ResponsesQuirks(providerTag = "t")
private val QUIRKS_ON = ResponsesQuirks(providerTag = "t", toolSurface = POLICY)

class ToolSurfaceTest {

    @Test
    fun `feature off - every tool eager, deferred empty`() {
        val body = requestOf(mcpTools(20) + builtins(16))
        val partition = ToolPartitioner(QUIRKS_OFF).partitionTools(body, opts())
        assertEquals(body.tools, partition.eager)
        assertTrue(partition.deferred.isEmpty())
    }

    @Test
    fun `non-lite model with the policy set - every tool eager`() {
        val body = requestOf(mcpTools(20) + builtins(16))
        val partition = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts(model = "gpt-5.5"))
        assertEquals(body.tools, partition.eager)
        assertTrue(partition.deferred.isEmpty())
    }

    @Test
    fun `compact turn - every tool eager`() {
        val body = requestOf(mcpTools(20) + builtins(16))
        val partition = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts(compact = true))
        assertEquals(body.tools, partition.eager)
        assertTrue(partition.deferred.isEmpty())
    }

    @Test
    fun `latch closed - every tool eager`() {
        val body = requestOf(mcpTools(20) + builtins(16))
        val partition = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts(toolSurfaceOpen = false))
        assertEquals(body.tools, partition.eager)
        assertTrue(partition.deferred.isEmpty())
    }

    @Test
    fun `mcp prefix rule - 50 MCP deferred, 16 built-ins eager`() {
        val body = requestOf(builtins(16) + mcpTools(50))
        val partition = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts())
        assertEquals(50, partition.deferred.size)
        assertEquals(16, partition.eager.size)
        assertTrue(partition.deferred.all { it.name.startsWith("mcp__") })
    }

    // Cache-prefix stability (2026-07-25): the partition must NOT react to transcript content —
    // a tool named by a ToolUseBlock in history is deferred or eager exactly as if that history
    // never happened. This test must never be deleted; it is the wall against the cache-bust bug
    // the removed R2 always-eager promotion caused (a deferred tool's first real use used to flip
    // additional_tools' bytes mid-conversation). The declaration-replay successor that keeps
    // replayed history self-describing without touching the partition is pinned in
    // ResponsesRequestBuilderTest.kt (ToolSurfaceRequestTest).
    @Test
    fun `a conversation whose history contains a tool_use for an mcp tool does not change the partition`() {
        val mcp = mcpTools(10)
        val warmed = mcp[3]
        val messages = listOf(
            AnthropicMessage(role = "assistant", content = listOf(ToolUseBlock(id = "t1", name = warmed.name))),
        )
        val cold = requestOf(builtins(16) + mcp)
        val warm = requestOf(builtins(16) + mcp, messages = messages)
        val coldPartition = ToolPartitioner(QUIRKS_ON).partitionTools(cold, opts())
        val warmPartition = ToolPartitioner(QUIRKS_ON).partitionTools(warm, opts())
        assertEquals(coldPartition.eager.map { it.name }, warmPartition.eager.map { it.name })
        assertEquals(coldPartition.deferred.map { it.name }, warmPartition.deferred.map { it.name })
        // the warmed tool still defers, exactly as it would with no history at all
        assertTrue(warmPartition.deferred.any { it.name == warmed.name })
        assertEquals(setOf(warmed.name), ToolPartitioner(QUIRKS_ON).warmToolNames(warm))
    }

    @Test
    fun `defer list forces a tool deferred even without a matching prefix`() {
        val builtinTask = ToolDefinition(name = "Task")
        val body = requestOf(mcpTools(10) + builtins(15) + builtinTask)
        val policy = ToolDeferralPolicy(defer = setOf("Task"))
        val quirks = ResponsesQuirks(providerTag = "t", toolSurface = policy)
        val partition = ToolPartitioner(quirks).partitionTools(body, opts())
        assertTrue(partition.deferred.any { it.name == "Task" })
    }

    @Test
    fun `eager list wins over the prefix rule`() {
        val forcedEager = ToolDefinition(name = "mcp__exa__web_search_exa")
        val body = requestOf(mcpTools(10) + builtins(16) + forcedEager)
        val policy = ToolDeferralPolicy(eager = setOf("mcp__exa__web_search_exa"))
        val quirks = ResponsesQuirks(providerTag = "t", toolSurface = policy)
        val partition = ToolPartitioner(quirks).partitionTools(body, opts())
        assertTrue(partition.eager.any { it.name == "mcp__exa__web_search_exa" })
        assertFalse(partition.deferred.any { it.name == "mcp__exa__web_search_exa" })
    }

    @Test
    fun `the tool_choice-named tool is never deferred`() {
        val mcp = mcpTools(10)
        val chosen = mcp[5]
        val body = requestOf(builtins(16) + mcp, toolChoice = ToolChoice(type = "tool", name = chosen.name))
        val partition = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts())
        assertTrue(partition.eager.any { it.name == chosen.name })
        assertFalse(partition.deferred.any { it.name == chosen.name })
    }

    @Test
    fun `min_deferred floor - 7 MCP tools all eager, 8 splits`() {
        val below = requestOf(builtins(16) + mcpTools(7))
        val belowPartition = ToolPartitioner(QUIRKS_ON).partitionTools(below, opts())
        assertTrue(belowPartition.deferred.isEmpty())

        val atFloor = requestOf(builtins(16) + mcpTools(8))
        val atFloorPartition = ToolPartitioner(QUIRKS_ON).partitionTools(atFloor, opts())
        assertEquals(8, atFloorPartition.deferred.size)
    }

    @Test
    fun `degenerate config never empties the eager set`() {
        val body = requestOf(mcpTools(20))
        val policy = ToolDeferralPolicy(deferPrefixes = listOf(""))
        val quirks = ResponsesQuirks(providerTag = "t", toolSurface = policy)
        val partition = ToolPartitioner(quirks).partitionTools(body, opts())
        assertTrue(partition.eager.isNotEmpty())
        assertTrue(partition.deferred.isEmpty())
    }

    @Test
    fun `order stability and idempotence`() {
        val body = requestOf(builtins(16) + mcpTools(20))
        val first = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts())
        val second = ToolPartitioner(QUIRKS_ON).partitionTools(body, opts())
        assertEquals(first, second)
        assertEquals(body.tools.filter { it in first.eager }, first.eager, "eager preserves body.tools order")
    }

    @Test
    fun `isToolSurfaceRejection fires on a shape-400, not an unrelated one`() {
        assertTrue(recovery.isToolSurfaceRejection(400, """{"error":{"message":"Unknown parameter: 'tool_search'"}}"""))
        assertTrue(recovery.isToolSurfaceRejection(422, "unsupported field defer_loading"))
        assertFalse(recovery.isToolSurfaceRejection(400, "rate limit exceeded"))
        assertFalse(recovery.isToolSurfaceRejection(500, "tool_search unsupported"), "status outside 400..422")
    }

    @Test
    fun `dropToolSearchTool removes exactly the tool_search entry, null when absent`() {
        val withSearch = """{"model":"gpt-5.6-sol","input":[
            {"type":"additional_tools","role":"developer","tools":[
                {"type":"function","name":"Bash","description":"d","parameters":{"type":"object","properties":{}}},
                {"type":"tool_search","execution":"client","description":"x",
                 "parameters":{"type":"object","properties":{}}}
            ]},
            {"role":"developer","content":"hi"}
        ],"store":false,"stream":true}"""
        val stripped = recovery.dropToolSearchTool(withSearch)
        assertTrue(stripped != null)
        val parsed = Json.parseToJsonElement(stripped!!).jsonObject
        val toolsArr = parsed["input"]!!.jsonArray[0].jsonObject["tools"]!!.jsonArray
        assertEquals(1, toolsArr.size)
        assertEquals("Bash", toolsArr[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("gpt-5.6-sol", parsed["model"]?.jsonPrimitive?.content)

        val withoutSearch = """{"model":"m","input":[
            {"type":"additional_tools","role":"developer","tools":[
                {"type":"function","name":"Bash","description":"d","parameters":{"type":"object","properties":{}}}
            ]}
        ],"store":false,"stream":true}"""
        assertNull(recovery.dropToolSearchTool(withoutSearch))
    }

    // review 2026-07-24 (known HIGH): the first cut of this recovery stripped only the tool_search
    // TOOL, leaving the invented tool_search_call/tool_search_output items (and their preceding
    // reasoning item) in `input` — a rejection of THOSE items could never be recovered by the
    // one-shot amend budget. The retry must be a clean status-quo-shaped request.
    @Test
    fun `dropToolSearchTool also strips tool_search_call, tool_search_output, and the dangling reasoning before it`() {
        val withSearchRound = """{"model":"gpt-5.6-sol","input":[
            {"type":"additional_tools","role":"developer","tools":[
                {"type":"function","name":"Bash","description":"d","parameters":{"type":"object","properties":{}}},
                {"type":"tool_search","execution":"client","description":"x",
                 "parameters":{"type":"object","properties":{}}}
            ]},
            {"role":"developer","content":"hi"},
            {"type":"reasoning","id":"rs_1","encrypted_content":"enc"},
            {"type":"tool_search_call","call_id":"ts_1","execution":"client","arguments":"{}"},
            {"type":"tool_search_output","call_id":"ts_1","status":"completed","execution":"client","tools":[]}
        ],"store":false,"stream":true}"""
        val stripped = recovery.dropToolSearchTool(withSearchRound)
        assertTrue(stripped != null)
        val input = Json.parseToJsonElement(stripped!!).jsonObject["input"]!!.jsonArray

        val toolsArr = input[0].jsonObject["tools"]!!.jsonArray
        assertEquals(1, toolsArr.size, "the tool_search TOOL entry is stripped")
        assertEquals("Bash", toolsArr[0].jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("hi", input[1].jsonObject["content"]?.jsonPrimitive?.content, "unrelated items survive")
        assertEquals(
            2,
            input.size,
            "the reasoning item, the invented call, and the invented output are ALL gone — " +
                "a clean status-quo-shaped retry, no dangling reasoning, no orphan output",
        )
    }
}
