// NEW: CodexProvider.extraHeaders is the SOLE controller of ChatGPT-Account-ID (UpstreamClient no
// longer adds it in applyAuth — that made account_id_header=false a no-op). This pins the gate:
// flag=true + a Bearer with an account id => header present; flag=false => absent, even with an
// account id available.
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.auth.AuthDescription
import splice.core.auth.Credentials
import splice.core.auth.RefreshableAuthProvider
import splice.core.model.ModelCatalog
import splice.core.model.ModelEntry
import splice.core.parse.parseAnthropicBody
import splice.core.turn.ReasoningDisplay
import splice.core.turn.WatchdogBudget
import splice.dialect.responses.ToolDeferralPolicy
import splice.provider.codex.CodexProvider
import splice.spi.ProviderTuning
import kotlin.time.Duration.Companion.seconds

class CodexProviderTest {

    private val fakeAuth = object : RefreshableAuthProvider {
        override suspend fun credentials() = Credentials.Bearer("tok", accountId = "acct-123")
        override suspend fun refresh() = credentials()
        override suspend fun describe() = AuthDescription(true, "chatgpt-oauth", emptyMap())
    }

    private fun provider(
        accountIdHeader: Boolean,
        toolSurface: ToolDeferralPolicy? = null,
        log: (String) -> Unit = {},
    ) = CodexProvider(
        tuning = ProviderTuning(
            key = "codex",
            label = "claudex",
            catalog = ModelCatalog(
                discoveryPrefix = "claude-codex--",
                models = listOf(ModelEntry("gpt-5.6-sol", "Sol", contextWindow = 272000L)),
                defaultContextWindow = 272000L,
            ),
            pinnedModel = "gpt-5.6-sol",
            auth = fakeAuth,
            baseUrl = "https://x",
            watchdog = WatchdogBudget(5.seconds, 3.seconds, 30.seconds),
        ),
        showReasoning = ReasoningDisplay.TEXT,
        replayReasoning = false,
        configEffort = "high",
        configSummary = "detailed",
        quirks = CodexProvider.defaultQuirks().copy(toolSurface = toolSurface),
        accountIdHeader = accountIdHeader,
        log = log,
    )

    @Test
    fun `account id header present when the flag is on`() = runBlocking {
        val creds = Credentials.Bearer("tok", accountId = "acct-123")
        val headers = provider(accountIdHeader = true).extraHeaders(creds)
        assertEquals("acct-123", headers["ChatGPT-Account-ID"])
        assertEquals("text/event-stream", headers["Accept"])
    }

    @Test
    fun `account id header absent when the flag is off - even with an account id available`() = runBlocking {
        val creds = Credentials.Bearer("tok", accountId = "acct-123")
        val headers = provider(accountIdHeader = false).extraHeaders(creds)
        assertFalse(headers.containsKey("ChatGPT-Account-ID")) // the flag actually suppresses it
        assertTrue(headers.containsKey("Accept"))
    }

    // A turn body with 1 builtin (eager) + 10 mcp-prefixed tools (deferrable at minDeferred=4).
    private fun deferrableTurnBody() = parseAnthropicBody(
        """{"model":"claude-codex--gpt-5.6-sol","stream":true,"max_tokens":1024,"system":"s",""" +
            """"tools":[{"name":"Read","input_schema":{"type":"object"}},$TEN_MCP_TOOLS],""" +
            """"messages":[{"role":"user","content":"hi"}]}""",
    )

    /** Runs [block] with stderr captured, restoring the original stream in [finally] — same
     *  idiom as DoctorCommandTest's System.setOut capture. */

    // review 2026-07-25 (PR top-level comment 7): the latch-close path (amendBodyOnFailure ->
    // dropToolSearchTool -> ToolSurfaceLatch.close) had no direct test — every existing test
    // exercises dropToolSearchTool as a free function (ToolSurfaceTest.kt) or the partition in
    // isolation, never through a real provider instance's amendBodyOnFailure, and never checking
    // that a LATER buildTurn on that same instance actually restores full eager. Drives the real
    // sequence end to end and pins the ResponsesProvider.logToolSurfaceLatchClosed signal
    // (comment 2) firing exactly once, not once per amend attempt or per turn.
    @Test
    fun `a shape-400 through amendBodyOnFailure strips tool_search, closes the latch once, next turn is eager`() {
        // 2026-07-27: the latch signal moved off bare stderr onto the injected daemon sink (wall
        // kt-no-println) so it reaches /mgmt/logs. Capture the sink — capturing stderr would now
        // pass vacuously with zero lines, which is exactly the regression this assertion guards.
        val loggedLines = mutableListOf<String>()
        val deferring = provider(
            accountIdHeader = false,
            toolSurface = ToolDeferralPolicy(minDeferred = 4),
            log = { loggedLines += it },
        )
        val body = deferrableTurnBody()
        val before = deferring.buildTurn(body, compact = false, sessionId = "s1")
        assertEquals(10, before.meta.toolsDeferred, "setup: this turn actually deferred the mcp tools")
        assertEquals(1, before.meta.toolsEager)

        val amended = deferring.amendBodyOnFailure(SHAPE_400, SHAPE_400_TEXT, REJECTED_TOOL_SEARCH_BODY)
        // a second rejection on the now-closed latch must not fire a second log line
        deferring.amendBodyOnFailure(SHAPE_400, SHAPE_400_TEXT, REJECTED_TOOL_SEARCH_BODY)
        val after = deferring.buildTurn(body, compact = false, sessionId = "s1")

        assertTrue(amended != null)
        val amendedInput = Json.parseToJsonElement(amended!!).jsonObject["input"]!!.jsonArray
        assertEquals(1, amendedInput[0].jsonObject["tools"]!!.jsonArray.size, "tool_search TOOL entry stripped")
        val strippedTypes = setOf("tool_search_call", "tool_search_output", "reasoning")
        assertTrue(
            amendedInput.none { it.jsonObject["type"]?.jsonPrimitive?.content in strippedTypes },
            "the synthesized call/output items and their now-dangling reasoning are stripped too",
        )

        assertEquals(0, after.meta.toolsDeferred, "latch closed -> the next turn builds the full eager surface")
        assertEquals(11, after.meta.toolsEager, "all 11 tools (1 builtin + 10 mcp) ride eager now")
        assertEquals(1, loggedLines.count { "tool-surface latch closed" in it }, "fires exactly once, not per turn")
    }
}

private const val SHAPE_400 = 400
private const val SHAPE_400_TEXT = "Unknown parameter: 'tool_search'"

private val TEN_MCP_TOOLS = (1..10).joinToString(",") {
    """{"name":"mcp__exa__tool_$it","input_schema":{"type":"object"}}"""
}

// A realistic post-rejection body: the additional_tools tool_search TOOL entry, plus a
// synthesized reasoning + tool_search_call + tool_search_output round already appended to
// `input` — same fixture shape as ToolSurfaceTest.kt's dropToolSearchTool tests, driven here
// through the real provider instance (CodexProviderTest) instead of the free function.
private const val REJECTED_TOOL_SEARCH_BODY = """{"model":"gpt-5.6-sol","input":[
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
