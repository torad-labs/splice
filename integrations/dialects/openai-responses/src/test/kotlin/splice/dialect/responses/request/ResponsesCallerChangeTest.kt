// Goal (muse-tool-cache, muse-tool-change): on a head whose backend caches by prefix with the tool list before the
// input, a tool a server adds or takes away leaves the request's tool list as the conversation's first request sent it.
// An added tool is written into the history where it was added, a name past Meta's 64 characters whole, and the model
// calls it through the caller; a removed tool is told in words where it went; a tool that comes back with a new schema
// is written again where it came back. Anyone can tell by reading the requests the head sends as the conversation
// grows.
package splice.dialect.responses.request

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import splice.core.parse.AnthropicParse
import splice.core.turn.ReasoningDisplay
import splice.dialect.responses.ResponsesLiteQuirks
import splice.dialect.responses.ResponsesQuirks
import splice.dialect.responses.ResponsesToolChanges
import splice.dialect.responses.ResponsesToolQuirks
import splice.dialect.responses.reasoning.InjectPriorReasoning

private const val CALLER = "CallToolByName"
private const val LONG = "mcp__claude_ai_Team_Workspace_Calendar__list_the_events_of_one_person_for_a_week"

private const val TOOLS = """
  {"name":"Bash","description":"Run a command",
   "input_schema":{"type":"object","properties":{"command":{"type":"string"}}}},
  {"name":"mcp__desk__run_process","description":"Run a process",
   "input_schema":{"type":"object","properties":{"command":{"type":"string"}}}}
"""

private const val OPENING = """{"role":"user","content":"Start."},{"role":"assistant","content":"Ready."}"""

private fun definition(name: String, vararg props: String): String =
    """{"name":"$name","description":"what $name does","input_schema":{"type":"object","properties":{""" +
        props.joinToString(",") { """"$it":{"type":"string"}""" } + "}}}"

private fun added(vararg definitions: String): String =
    """{"role":"system","content":[{"type":"text","text":"These tools are now available."},""" +
        definitions.joinToString(",") { addition(it) } + "]}"

private fun addition(definition: String): String =
    """{"type":"tool_addition","tool":{"type":"tool_definition","definition":$definition}}"""

private fun removed(vararg names: String): String =
    """{"role":"system","content":[{"type":"text","text":"These tools are no longer available."},""" +
        names.joinToString(",") { """{"type":"tool_removal","tool":{"type":"tool_reference","name":"$it"}}""" } + "]}"

private fun called(id: String, name: String, input: String): String =
    """{"role":"assistant","content":[{"type":"tool_use","id":"$id","name":"$name","input":$input}]},""" +
        """{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"done"}]}"""

private const val SAY = """{"role":"user","content":"Go on."}"""

class ResponsesCallerChangeTest {
    private val quirks = ResponsesQuirks(
        providerTag = "muse",
        lite = ResponsesLiteQuirks(),
        tools = ResponsesToolQuirks(toolChanges = ResponsesToolChanges.THROUGH_CALLER),
    )

    @Test
    fun `an added tool is written where it was added, and the tool list stays as it was`() {
        val first = build(OPENING, SAY)
        val addition = added(definition("mcp__docs__create_doc", "title"), definition(LONG, "person"))
        val request = build(OPENING, addition, SAY)

        assertEquals(first.getValue("tools"), request.getValue("tools"))
        assertEquals(listOf("Bash", "mcp__desk__run_process", CALLER), listed(request))
        val after = text(input(request).drop(input(first).size - 1))
        assertTrue("mcp__docs__create_doc" in after && "title" in after, after)
        assertTrue(LONG in after, "a name past 64 characters is written whole: $after")
        assertTrue(input(request).none { "additional_tools" == it["type"]?.jsonPrimitive?.content })
        assertEquals(input(first).dropLast(1), input(request).take(input(first).size - 1), "the change only adds")
    }

    @Test
    fun `the model's call of an added tool goes back through the caller, its name whole`() {
        val addition = added(definition(LONG, "person"))
        val request = build(OPENING, addition, called("toolu_1", LONG, """{"person":"ana"}"""), SAY)

        val call = input(request).single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(CALLER, call["name"]?.jsonPrimitive?.content)
        assertEquals("""{"tool":"$LONG","arguments":{"person":"ana"}}""", call["arguments"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool the conversation started with is told gone, and the tool list and history stay`() {
        val ran = called("toolu_1", "mcp__desk__run_process", """{"command":"ls"}""")
        val before = build(OPENING, ran, SAY)
        val request = build(OPENING, ran, removed("mcp__desk__run_process"), SAY)

        assertEquals(before.getValue("tools"), request.getValue("tools"))
        assertEquals(input(before).dropLast(1), input(request).take(input(before).size - 1))
        val told = text(input(request).drop(input(before).size - 1))
        assertTrue("mcp__desk__run_process" in told, told)
    }

    @Test
    fun `an added tool taken away again is told gone, and the one that stayed is still called through the caller`() {
        val addition = added(definition("mcp__docs__create_doc", "title"), definition("mcp__docs__update_doc", "id"))
        val before = build(OPENING, addition, SAY)
        val request = build(
            OPENING,
            addition,
            removed("mcp__docs__create_doc"),
            called("toolu_2", "mcp__docs__update_doc", """{"id":"1"}"""),
            SAY,
        )

        assertEquals(before.getValue("tools"), request.getValue("tools"))
        assertEquals(input(before).dropLast(1), input(request).take(input(before).size - 1))
        val told = text(input(request).drop(input(before).size - 1))
        assertTrue("mcp__docs__create_doc" in told, told)
        val call = input(request).single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(CALLER, call["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a tool that comes back with a new schema is written again where it came back`() {
        val docs = definition("mcp__docs__create_doc", "title")
        val gone = listOf(OPENING, added(docs), removed("mcp__docs__create_doc"))
        val before = build(*gone.toTypedArray(), SAY)
        val request = build(*gone.toTypedArray(), added(definition("mcp__docs__create_doc", "title", "folder")), SAY)

        assertEquals(before.getValue("tools"), request.getValue("tools"))
        val back = text(input(request).drop(input(before).size - 1))
        assertTrue("mcp__docs__create_doc" in back && "folder" in back, "the new schema is written: $back")
    }

    @Test
    fun `a starting tool that comes back with a new schema is written, and called through the caller from there`() {
        val back = added(definition("mcp__desk__run_process", "command", "cwd"))
        val request = build(
            OPENING,
            removed("mcp__desk__run_process"),
            back,
            called("toolu_3", "mcp__desk__run_process", """{"command":"ls","cwd":"/"}"""),
            SAY,
        )

        assertEquals(listOf("Bash", "mcp__desk__run_process", CALLER), listed(request))
        assertTrue("cwd" in text(input(request)), "the new schema is written")
        val call = input(request).single { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals(CALLER, call["name"]?.jsonPrimitive?.content)
    }

    private fun build(vararg messages: String): JsonObject {
        val parsed = AnthropicParse.parseAnthropicBody(
            """{"model":"m","tools":[$TOOLS],"messages":[${messages.joinToString(",")}]}""",
        )
        val options = BuildOptions(
            compact = false,
            models = ModelIds(original = "claude-muse--muse-wonder-full-1.0", upstream = "muse-wonder-full-1.0"),
            reasoning = RequestedReasoning(effort = null, summary = null, display = ReasoningDisplay.OFF),
            handoff = ReasoningHandoff(replay = InjectPriorReasoning(false), decode = { null }),
        )
        return ResponsesRequestBuilder(quirks).build(parsed.typed, parsed.raw, options).req
    }
}

private fun input(request: JsonObject): List<JsonObject> = request.getValue("input").jsonArray.map { it.jsonObject }

private fun listed(request: JsonObject): List<String> =
    request.getValue("tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }

private fun text(items: List<JsonObject>): String = items.joinToString("\n") { it.toString() }
