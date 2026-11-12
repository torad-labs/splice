// Goal (Marcos, 2026-10-25, "both"): when Claude Code loads a tool in the middle of a code-mode conversation, the
// request's first item (the additional_tools declaration that carries exec's manual) stays byte for byte what the
// conversation's earlier requests sent, so the provider's prompt cache still covers the whole conversation. The live
// case, 2:05 CT on Oct 25 (turn 9e239a563d4d): a cell ran `tools.ToolSearch({query:"select:Monitor"})`, the next
// request's manual grew by Monitor's section (42251 to 49555 characters), and the turn read 0 cached tokens right after
// a 99% hit. The model reads about the loaded tool where the load happened instead: in the search's own result, which
// the conversation only ever appends to. That result reached the cell as "[non-text content omitted …]", so the model
// learned nothing from it. And the loaded tool stays callable from a cell.
package splice.provider.codex

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.upstream.BuiltTurn
import splice.upstream.RoundResult
import splice.upstream.codemode.CodeModeRuntime
import splice.upstream.codemode.CodeModeStep

class CodeModeLoadedToolSurfaceTest : CodeModeBridgeTestSupport() {
    @Test
    fun `a tool loaded mid-conversation leaves the first item byte for byte as the turn before sent it`() {
        val builder = builder(ScriptedRuntime(ArrayDeque()))
        val before = builder.prepare(body(loaded = false), SESSION, built(listOf(READ, SEARCH)))
        val after = builder.prepare(body(loaded = true), SESSION, built(listOf(READ, SEARCH, MONITOR)))

        assertEquals(firstItem(before), firstItem(after))
    }

    @Test
    fun `the search result the cell reads names the loaded tool and how to call it`() {
        val results = builder(ScriptedRuntime(ArrayDeque())).toolResults(body(loaded = true))
        val result = results.single { it.id == SEARCH_ID }

        assertTrue("tools.Monitor" in result.output, result.output)
        assertTrue("Start a background monitor." in result.output, result.output)
        assertTrue("declare const tools: { Monitor(" in result.output, result.output)
        assertFalse("omitted" in result.output, result.output)
    }

    /** A guard more than a new behavior: the cell's callable set is the client's whole catalog, and leaving the loaded
     *  tool out of the manual must not take it out of that set. */
    @Test
    fun `the loaded tool stays callable from a cell`() = runTest {
        val runtime = ScriptedRuntime(ArrayDeque(listOf(CodeModeStep.Calls(listOf(call("cell-call", "Monitor"))))))
        val prepared = builder(runtime).prepare(body(loaded = true), SESSION, built(listOf(READ, SEARCH, MONITOR)))
        val sink = RecordingSink()
        checkNotNull(prepared.roundInterceptor).intercept(prepared.requestBody.toString(), sink) {
            RoundResult.Outcome(outerOutcome())
        }

        assertEquals(listOf("Monitor"), sink.tools.map { it.name })
    }

    private fun builder(runtime: CodeModeRuntime) =
        CodexCodeModeTurnBuilder(bridge(runtime), media(), codeModeOnly = backendCodeModeOnly)

    /** The Anthropic body Claude Code sends: before the load, its catalog without Monitor; after it, the cell's
     *  ToolSearch call, its result naming Monitor as a tool_reference, and Monitor in the catalog. */
    private fun body(loaded: Boolean) = AnthropicParse.parseAnthropicBody(
        """{"model":"gpt-6-astra","max_tokens":100,"tools":[""" +
            (if (loaded) TOOLS else TOOLS.dropLast(1)).joinToString(",") + "]," +
            """"messages":[{"role":"user","content":"start"}""" +
            (if (loaded) LOAD_HISTORY else "") + "]}",
    )

    /** The Responses lite request the dialect built for those tools, as code mode receives it. */
    private fun built(tools: List<String>): BuiltTurn {
        val base = built("gpt-6-astra", lite = true)
        val input = base.requestBody.getValue("input").jsonArray
        val declared = JsonArray(tools.map { Json.parseToJsonElement(it) })
        val first = JsonObject(input[0].jsonObject + ("tools" to declared))
        val rebuilt = JsonArray(listOf(first) + input.drop(1))
        return base.copy(requestBody = JsonObject(base.requestBody + ("input" to rebuilt)))
    }

    private fun firstItem(turn: BuiltTurn): String = turn.requestBody.getValue("input").jsonArray[0].toString()
}

private const val SESSION = "session-a"
private const val SEARCH_ID = "toolu_splice_search"

private const val READ =
    """{"type":"function","name":"Read","description":"Reads a file.","parameters":{"type":"object"}}"""
private const val SEARCH =
    """{"type":"function","name":"ToolSearch","description":"Fetches deferred tools.","parameters":{"type":"object"}}"""
private const val MONITOR = """{"type":"function","name":"Monitor","description":"Start a background monitor.",""" +
    """"parameters":{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}}"""

private val TOOLS = listOf(
    """{"name":"Read","description":"Reads a file.","input_schema":{"type":"object"}}""",
    """{"name":"ToolSearch","description":"Fetches deferred tools.","input_schema":{"type":"object"}}""",
    """{"name":"Monitor","description":"Start a background monitor.","input_schema":{"type":"object",""" +
        """"properties":{"command":{"type":"string"}},"required":["command"]}}""",
)

private val LOAD_HISTORY =
    """,{"role":"assistant","content":[{"type":"tool_use","id":"$SEARCH_ID","name":"ToolSearch",""" +
        """"input":{"query":"select:Monitor","max_results":1}}]},""" +
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$SEARCH_ID",""" +
        """"content":[{"type":"tool_reference","tool_name":"Monitor"}]},{"type":"text","text":"Tool loaded."}]}"""
